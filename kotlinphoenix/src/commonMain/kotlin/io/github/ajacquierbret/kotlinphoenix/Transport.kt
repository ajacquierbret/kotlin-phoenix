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

package io.github.ajacquierbret.kotlinphoenix

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

sealed class SocketEvent: Exception() {
  /** Called when the Transport opens */
  object OpenEvent: SocketEvent()
  /** Called when the Transport receives an error */
  data class FailureEvent(val throwable: Throwable, val response: Any?): SocketEvent()
  /** Called each time the Transport receives a message */
  data class MessageEvent(val text: Message): SocketEvent()
  /** Called when the Transport closes */
  data class CloseEvent(val code: Int): SocketEvent()
}

/**
 * Interface that defines different types of Transport layers.
 */
interface Transport {

  /** Available ReadyStates of a $Transport. */
  enum class ReadyState {

    /** The Transport is connecting to the server  */
    CONNECTING,

    /** The Transport is connected and open */
    OPEN,

    /** The Transport is closing */
    CLOSING,

    /** The Transport is closed */
    CLOSED
  }

  /** The state of the Transport. See {@link ReadyState} */
  val readyState: ReadyState

  /** Connect to the server */
  fun connect(): SocketFlow

  /** Connect to the server and suspends until the socket is open */
  suspend fun connectSuspend(): SocketFlow

  /**
   * Disconnect from the Server
   *
   * @param code Status code as defined by <a
   * href="http://tools.ietf.org/html/rfc6455#section-7.4">Section 7.4 of RFC 6455</a>.
   * @param reason Reason for shutting down or {@code null}.
   */
  fun disconnect(code: Int, reason: String? = null)

  /**
   * Sends text to the Server
   */
  fun send(data: String)
}

abstract class WebSocketTransportCommon: Transport {
  internal val _sharedFlow: MutableSharedFlow<SocketEvent> = MutableSharedFlow(8 * 1024)
  internal val sharedFlow = _sharedFlow.asSharedFlow()

  override var readyState: Transport.ReadyState = Transport.ReadyState.CLOSED

  override suspend fun connectSuspend(): SocketFlow {
    val connection = connect()

    val event = connection.first { it is SocketEvent.OpenEvent }

    if  (event is SocketEvent.OpenEvent) {
      return connection
    } else throw event
  }
}

/**
 * A WebSocket implementation of a Transport that uses a WebSocket to facilitate sending
 * and receiving data.
 *
 * @param url: URL to connect to
 */
expect class WebSocketTransport(
  url: URL,
  decode: DecodeClosure
): WebSocketTransportCommon {
  val url: URL
  val decode: DecodeClosure
}