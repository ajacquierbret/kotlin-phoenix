/*
 * Copyright (c) 2019 Daniel Rees <daniel.rees18@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.github.ajacquierbret.kotlinphoenix.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Represents a Channel bound to a given topic
 */
class Channel(
    val topic: String,
    params: Payload,
    internal val socket: Socket,
    internal val socketFlow: SocketFlow,
    private val scope: CoroutineScope
): SharedFlow<Message> {

  //------------------------------------------------------------------------------
  // Channel Nested Enums
  //------------------------------------------------------------------------------
  /** States of a Channel */
  enum class State {
    CLOSED,
    ERRORED,
    JOINED,
    JOINING,
    LEAVING
  }

  /** Channel specific events */
  enum class Event(val value: String) {
    HEARTBEAT("heartbeat"),
    JOIN("phx_join"),
    LEAVE("phx_leave"),
    REPLY("phx_reply"),
    ERROR("phx_error"),
    CLOSE("phx_close");

    companion object {
      /** True if the event is one of Phoenix's channel lifecycle events */
      fun isLifecycleEvent(event: String): Boolean {
        return when (event) {
          JOIN.value,
          LEAVE.value,
          REPLY.value,
          ERROR.value,
          CLOSE.value -> true
          else -> false
        }
      }
    }
  }

  //------------------------------------------------------------------------------
  // Channel Attributes
  //------------------------------------------------------------------------------
  private val _channel: MutableSharedFlow<Message> = MutableSharedFlow(8 * 1024)
  private val channel: SharedFlow<Message> = _channel.asSharedFlow()

  override suspend fun collect(collector: FlowCollector<Message>): Nothing = channel.collect(collector)
  override val replayCache = channel.replayCache

  /** Current state of the Channel */
  private var state: State = State.CLOSED

  /** Timeout when attempting to join a Channel */
  private var timeout: Long = socket.timeout

  /** Params passed in through constructions and provided to the JoinPush */
  var params: Payload = params
    set(value) {
      joinPush.payload = value
      field = value
    }

  /** Set to true once the channel has attempted to join */
  private var joinedOnce: Boolean = false

  /** Push to send then attempting to join */
  private var joinPush: Push = Push(
    channel = this@Channel,
    event = Event.JOIN.value,
    payload = params,
    timeout = timeout,
    scope = scope
  )

  /** Buffer of Pushes that will be sent once the Channel's socket connects */
  private var pushBuffer: MutableList<Push> = mutableListOf()

  /** Timer to attempt rejoins */
  private var rejoinTimer: TimeoutTimer = TimeoutTimer(
    timerCalculation = socket.rejoinAfterMs,
    scope = scope
  )

  init {
    // Respond to socket events
    launchSocketListener()

    // Handle Channel's join() responses
    launchJoinListener()

    // Handle Channel responses
    launchChannelListener()
  }

  //------------------------------------------------------------------------------
  // Public Properties
  //------------------------------------------------------------------------------
  /** The ref sent during the join message. */
  val joinRef: String? get() = joinPush.ref

  /** @return True if the Channel can push messages */
  private val canPush: Boolean
    get() = socket.isConnected && isJoined

  /** @return: True if the Channel has been closed */
  val isClosed: Boolean
    get() = state == State.CLOSED

  /** @return: True if the Channel experienced an error */
  val isErrored: Boolean
    get() = state == State.ERRORED

  /** @return: True if the channel has joined */
  val isJoined: Boolean
    get() = state == State.JOINED

  /** @return: True if the channel has requested to join */
  val isJoining: Boolean
    get() = state == State.JOINING

  /** @return: True if the channel has requested to leave */
  val isLeaving: Boolean
    get() = state == State.LEAVING

  //------------------------------------------------------------------------------
  // Public
  //------------------------------------------------------------------------------
  suspend fun join(timeout: Long = this.timeout): Push {
    // Ensure that `.join()` is called only once per Channel instance
    if (joinedOnce) {
      throw IllegalStateException(
          "Tried to join channel multiple times. `join()` can only be called once per channel")
    }

    // Join the channel
    this@Channel.timeout = timeout
    joinedOnce = true
    rejoin()
    return joinPush
  }

  suspend fun push(event: String, payload: Payload, timeout: Long = this.timeout): Push {
    if (!joinedOnce) {
      // If the Channel has not been joined, throw an exception
      throw RuntimeException(
          "Tried to push $event to $topic before joining. Use channel.join() before pushing events")
    }

    val pushEvent = Push(this@Channel, event, payload, timeout, scope)

    if (canPush) {
      pushEvent.send()
    } else {
      pushEvent.startTimeout()
      pushBuffer.add(pushEvent)
    }

    return pushEvent
  }

  suspend fun leave(timeout: Long = this.timeout): Push {
    // Can push is dependent upon state == JOINED. Once we set it to LEAVING, then canPush
    // will return false, so instead store it _before_ starting the leave
    val canPush = canPush

    // If attempting a rejoin during a leave, then reset, cancelling the rejoin
    rejoinTimer.reset()

    // Prevent entering a rejoin loop if leaving a channel before joined
    joinPush.cancelTimeout()

    // Now set the state to leaving
    state = State.LEAVING

    // Perform the same behavior if the channel leaves successfully or not
    val close: suspend ((Message) -> Unit) = {
      socket.logItems("Channel: leave $topic")
      tryEmit(Event.CLOSE, mapOf("reason" to "leave"))
    }

    // Push event to send to the server
    val leavePush = Push(
        channel = this,
        event = Event.LEAVE.value,
        timeout = timeout,
        scope = scope
    ).let { leavePush ->
      scope.launch(CoroutineName("CHANNEL_LEAVEPUSH_LISTENER_$topic")) {
        leavePush.collect {
          when (it.event) {
            "ok" -> close(it)
            "timeout" -> close(it)
            else -> Unit
          }
          cancel()
        }
      }
      return@let leavePush
    }

    leavePush.send()

    // If the Channel cannot send push events, trigger a success locally
    if (!canPush) leavePush.trigger("ok", hashMapOf())

    return leavePush
  }

  //------------------------------------------------------------------------------
  // Internal
  //------------------------------------------------------------------------------
  internal fun tryEmit(
      event: Event,
      payload: Payload = hashMapOf(),
      ref: String = "",
      joinRef: String? = null
  ) = tryEmit(event.value, payload, ref, joinRef)

  internal fun tryEmit(
      event: String,
      payload: Payload = hashMapOf(),
      ref: String = "",
      joinRef: String? = null
  ) = tryEmit(Message(joinRef, ref, topic, event, payload))

  internal fun tryEmit(element: Message) = _channel.tryEmit(element)

  /** Checks if a Message's event belongs to this Channel instance */
  internal fun isMember(message: Message): Boolean {
    if (message.topic != topic) return false

    val isLifecycleEvent = Event.isLifecycleEvent(message.event)

    // If the message is a lifecycle event and it is not a join for this channel, drop the outdated message
    if (message.joinRef != null && isLifecycleEvent && message.joinRef != joinRef) {
      socket.logItems("Channel: Dropping outdated message. ${message.topic}")
      return false
    }

    return true
  }

  /** Create an event with a given ref */
  internal fun replyEventName(ref: String): String {
    return "chan_reply_$ref"
  }

  //------------------------------------------------------------------------------
  // Private
  //------------------------------------------------------------------------------
  private fun launchSocketListener() {
    scope.launch(CoroutineName("CHANNEL_SOCKET_LISTENER_$topic")) {
      socketFlow.collect {
        when (it) {
          is SocketEvent.OpenEvent -> {
            rejoinTimer.reset()
            if (isErrored) {
              rejoin()
            }
          }
          is SocketEvent.FailureEvent -> rejoinTimer.reset()
          is SocketEvent.CloseEvent -> cancel()
          else -> Unit
        }
      }
    }
  }

  private fun launchJoinListener() {
    scope.launch(CoroutineName("CHANNEL_JOIN_LISTENER_$topic")) {
      joinPush.collect { message ->
        when (message.status) {
          // Perform once the Channel has joined
          "ok" -> onChannelJoined()
          // Perform if Channel errors while attempting to join
          "error" -> onChannelJoinError()
          // Perform if Channel timed out while attempting to join
          "timeout" -> onChannelJoinTimeout()
        }
        cancel()
      }
    }
  }

  private fun launchChannelListener() {
    scope.launch(CoroutineName("CHANNEL_RESPONSE_LISTENER_$topic")) {
      this@Channel.collect { message ->
        when (message.event) {
          // Clean up when the channel closes
          Event.CLOSE.value -> {
            onChannelClose()
            cancel()
          }
          // Handles an error, attempts to rejoin
          Event.ERROR.value -> onChannelError(message)
          // Perform when the join reply is received
          Event.REPLY.value -> {
            tryEmit(replyEventName(message.ref), message.rawPayload, message.ref, message.joinRef)
          }
          Event.LEAVE.value -> cancel()
        }
      }
    }
  }

  /** Sends the Channel's joinPush to the Server */
  private suspend fun sendJoin(timeout: Long) {
    state = State.JOINING
    joinPush.resend(timeout)
  }

  /** Rejoins the Channel e.g. after a disconnect */
  private suspend fun rejoin(timeout: Long = this.timeout) {
    // Do not attempt to rejoin if the channel is in the process of leaving
    if (isLeaving) return

    // Leave potentially duplicated channels
    socket.leaveOpenTopic(this.topic)

    // Send the joinPush
    sendJoin(timeout)
  }

  //------------------------------------------------------------------------------
  // Channel Responses Hooks
  //------------------------------------------------------------------------------
  private suspend fun onChannelJoined() {
    // Mark the Channel as joined
    state = State.JOINED

    // Reset the timer, preventing it from attempting to join again
    rejoinTimer.reset()

    // Send any buffered messages and clear the buffer
    pushBuffer.forEach { it.send() }
    pushBuffer.clear()
  }

  private suspend fun onChannelJoinError() {
    state = State.ERRORED
    if (socket.isConnected) {
      rejoinTimer.scheduleTimeout {
        rejoin()
      }
    }
  }

  private suspend fun onChannelJoinTimeout() {
    // Log the timeout
    socket.logItems("Channel: timeouts $topic, $joinRef after $timeout ms")

    // Send a Push to the server to leave the Channel
    val leavePush = Push(
      channel = this@Channel,
      event = Event.LEAVE.value,
      timeout = timeout,
      scope = scope
    )

    leavePush.send()

    // Mark the Channel as in an error and attempt to rejoin if socket is connected
    state = State.ERRORED
    joinPush.reset()

    if (socket.isConnected) {
      rejoinTimer.scheduleTimeout {
        rejoin()
      }
    }
  }

  private fun onChannelClose() {
    // Reset any timer that may be on-going
    rejoinTimer.reset()

    // Log that the channel was left
    socket.logItems("Channel: close $topic $joinRef")

    // Mark the channel as closed and remove it from the socket
    state = State.CLOSED
    socket.remove(this@Channel)
  }

  private fun onChannelError(message: Message) {
    // Log that the channel received an error
    socket.logItems("Channel: error $topic ${message.payload}")

    // If error was received while joining, then reset the Push
    if (isJoining) {
      // Make sure that the "phx_join" isn't buffered to send once the socket
      // reconnects. The channel will send a new join event when the socket connects.
      joinRef?.let { socket.removeFromSendBuffer(it) }

      // Reset the push to be used again later
      joinPush.reset()
    }

    // Mark the channel as errored and attempt to rejoin if socket is currently connected
    state = State.ERRORED
    if (socket.isConnected) {
      rejoinTimer.scheduleTimeout {
        rejoin()
      }
    }
  }
}