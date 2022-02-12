package io.github.ajacquierbret.kotlinphoenix.adapters.apollo

import com.apollographql.apollo3.api.*
import com.apollographql.apollo3.api.http.DefaultHttpRequestComposer
import com.apollographql.apollo3.api.json.jsonReader
import com.apollographql.apollo3.exception.ApolloNetworkException
import com.apollographql.apollo3.network.NetworkTransport
import com.apollographql.apollo3.network.ws.*
import com.benasher44.uuid.Uuid
import io.github.ajacquierbret.kotlinphoenix.client.Channel as PhxChannel
import io.github.ajacquierbret.kotlinphoenix.client.Channel.Event
import io.github.ajacquierbret.kotlinphoenix.client.Message
import io.github.ajacquierbret.kotlinphoenix.client.Payload
import io.github.ajacquierbret.kotlinphoenix.client.Socket as PhxSocket
import io.github.ajacquierbret.kotlinphoenix.client.SocketEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal sealed interface PhxMessage
internal sealed interface PhxCommand : PhxMessage
internal class StartOperation<D : Operation.Data>(val request: ApolloRequest<D>) : PhxCommand
internal class StopOperation<D : Operation.Data>(val request: ApolloRequest<D>) : PhxCommand
internal object Dispose: PhxCommand

internal sealed interface PhxEvent : PhxMessage {
    /**
     * the id of the operation
     * Might be null for general errors or network errors that are broadcast to all listeners
     */
    val id: String?
}

internal class OperationResponse(override val id: String?, val payload: Map<String, Any?>) :
    PhxEvent
internal class OperationError(override val id: String?, val payload: Map<String, Any?>?) : PhxEvent
internal class OperationComplete(override val id: String?) : PhxEvent
internal class GeneralError(val payload: Map<String, Any?>?) : PhxEvent {
    override val id: String? = null
}

internal class NetworkError(val cause: Throwable) : PhxEvent {
    override val id: String? = null
}

/**
 * A [NetworkTransport] that works with Phoenix Channels.
 *
 * @param serverUrl the url to use to establish the Phoenix Socket connection.
 * @param topic the topic to which the channel will subscribe
 * @param params the parameters to send to the server (e.g. authentication headers)
 * @param channelJoinTimeoutMs the time during which the channel will try to join before timing out
 * @param idleTimeoutMillis the idle time duration beyond which the socket will disconnect
 * @param reconnectWhen a function taking the error as a parameter and returning 'true' to reconnect
 *
 */
class PhoenixNetworkTransport private constructor(
    private val serverUrl: String,
    private val topic: String,
    private val params: Payload? = null,
    private val channelJoinTimeoutMs: Long = 10_000,
    private val idleTimeoutMillis: Long = 60_000,
    private val reconnectWhen: ((Throwable) -> Boolean)?,
) : NetworkTransport {
    /**
     * Use unlimited buffers so that we never have to suspend when writing a command or an event,
     * and we avoid deadlocks. This might be overkill but is most likely never going to be a problem in practice.
     */
    private val messages = Channel<PhxMessage>(Channel.UNLIMITED)

    /**
     * This takes messages from [messages] and broadcasts the [Event]s
     */
    private val mutableEvents = MutableSharedFlow<PhxEvent>(0, Int.MAX_VALUE, BufferOverflow.SUSPEND)
    private val events = mutableEvents.asSharedFlow()

    private val activeSubscriptions = mutableMapOf<String, String>()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        coroutineScope.launch {
            supervise(this)
        }
    }

    private val listener = object : WsProtocol.Listener {
        override fun operationResponse(id: String, payload: Map<String, Any?>) {
            messages.trySend(OperationResponse(id, payload))
        }

        override fun operationError(id: String, payload: Map<String, Any?>?) {
            messages.trySend(OperationError(id, payload))
        }

        override fun operationComplete(id: String) {
            messages.trySend(OperationComplete(id))
        }

        override fun generalError(payload: Map<String, Any?>?) {
            messages.trySend(GeneralError(payload))
        }

        override fun networkError(cause: Throwable) {
            messages.trySend(NetworkError(cause))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun absintheMessageToApolloMessage(message: Message? = null, type: String? = null): Map<String, Any?> {
        val subscriptionKey =
            (message?.payload as Map<String, Any>?)?.get("subscriptionId") as String?
                ?: message?.topic

        val subscriptionId = activeSubscriptions[subscriptionKey]

        return mapOf(
            "id" to subscriptionId,
            "payload" to message?.payload,
            "type" to (type ?: message?.status)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleServerMessage(messageMap: Map<String, Any?>) {
        val subscriptionId = messageMap["id"] as String?
        val payload = messageMap["payload"] as Map<String, Any?>?

        if (subscriptionId != null) {
            when (messageMap["type"]) {
                "ok" -> if (payload !== null) {
                    val result = payload["result"] as Map<String, Any?>?

                    if (result !== null) {
                        listener.operationResponse(
                            id = subscriptionId,
                            payload = result
                        )
                    }
                }
                "error" -> listener.operationError(subscriptionId, payload)
                "complete" -> listener.operationComplete(subscriptionId)
                else -> Unit
            }
        } else {
            when (messageMap["type"]) {
                "error" -> listener.generalError(payload)
                else -> Unit
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <D : Operation.Data> startOperation(channel: PhxChannel, request: ApolloRequest<D>) {
        coroutineScope.launch {
            channel.push(
                event = "doc",
                payload = DefaultHttpRequestComposer.composePayload(request)
            ).collect {
                when (it.status) {
                    "ok" -> {
                        val subscriptionKey =
                            (it.payload as Map<String, Any>?)?.get("subscriptionId") as String?
                        if (subscriptionKey !== null) {
                            activeSubscriptions[subscriptionKey] = request.requestUuid.toString()
                        }
                    }
                    "error" -> {
                        val messageMap = absintheMessageToApolloMessage(it, type = "error")
                        handleServerMessage(messageMap)
                    }
                }
                cancel()
            }
        }
    }

    private fun <D : Operation.Data> stopOperation(channel: PhxChannel, request: ApolloRequest<D>) {
        coroutineScope.launch {
            val subscriptionId = request.requestUuid.toString()
            val isActiveSubscription = activeSubscriptions.values.contains(subscriptionId)

            if (isActiveSubscription) {
                channel.push(
                    event = "unsubscribe",
                    payload = mapOf("subscriptionId" to subscriptionId)
                ).collect {
                    when (it.status) {
                        "ok" -> {
                            val payload: Payload = it.payload
                            val subscriptionKey = payload["subscriptionId"] as? String ?: it.topic

                            activeSubscriptions.remove(subscriptionKey)

                            val messageMap = absintheMessageToApolloMessage(it, type = "complete")
                            handleServerMessage(messageMap)
                        }
                    }
                    cancel()
                }
            }
        }
    }

    /**
     * Long-running method that creates/handles the socket lifecyle
     */
    private suspend fun supervise(scope: CoroutineScope) {
        /**
         * No need to lock these variables as they are all accessed from the same thread
         */
        var idleJob: Job? = null
        var connectionJob: Job? = null
        val activeMessages = mutableMapOf<Uuid, StartOperation<*>>()

        var socket: PhxSocket? = null
        var channel: PhxChannel? = null

        /**
         * This happens:
         * - when this coroutine receives a [Dispose] message
         * - when the idleJob completes
         * - when there is an error reading the socket and this coroutine receives a [NetworkError] message
         */
        suspend fun closeTransport() {
            connectionJob?.cancel()
            connectionJob = null
            channel?.leave()
            socket?.disconnect()
            channel = null
            socket = null
            idleJob?.cancel()
            idleJob = null
        }

        while (true) {
            val message = messages.receive()

            when (message) {
                is PhxEvent -> {
                    if (message is NetworkError) {
                        closeTransport()

                        if (reconnectWhen?.invoke(message.cause) == true) {
                            activeMessages.values.forEach {
                                // Re-queue all start messages
                                // Will reconnect the socket and
                                // try to rejoin the channel
                                messages.trySend(it)
                            }
                        } else {
                            // forward the NetworkError downstream. Active flows will throw
                            mutableEvents.tryEmit(message)
                        }
                    } else {
                        mutableEvents.tryEmit(message)
                    }
                }
                is PhxCommand -> {
                    if (socket == null && channel == null) {
                        if (message !is StartOperation<*>) {
                            // A stop was received, but we don't have a connection. Ignore it
                            continue
                        }

                        socket = PhxSocket(url = serverUrl, params = params, scope = scope)

                        val connection = try {
                            socket!!.connect()
                        } catch (e: SocketEvent.FailureEvent) {
                            // Error opening the socket
                            mutableEvents.emit(NetworkError(e))
                            continue
                        }

                        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            connection!!.collect {
                                when (it) {
                                    is SocketEvent.MessageEvent ->
                                        handleServerMessage(
                                            absintheMessageToApolloMessage(
                                                message = it.text,
                                                type = "ok"
                                            )
                                        )

                                    is SocketEvent.FailureEvent -> listener.networkError(it.throwable)
                                    else -> Unit
                                }
                            }
                        }

                        channel = socket!!.channel(topic, connection!!)

                        try {
                            withTimeout(channelJoinTimeoutMs) {
                                launch {
                                    channel!!.join().collect {
                                        when (it.status) {
                                            "ok" -> cancel()
                                            "error" -> throw SocketEvent.FailureEvent(Throwable("Connection error while joining phoenix channel : $it"), null)
                                            else -> println("Unhandled message status while joining phoenix channel : ${it.status}")
                                        }
                                    }
                                }
                            }
                        } catch (e: SocketEvent.FailureEvent) {
                            // Error initializing the connection
                            socket = null
                            channel = null
                            mutableEvents.emit(NetworkError(e))
                            continue
                        }
                    }

                    when (message) {
                        is StartOperation<*> -> {
                            activeMessages[message.request.requestUuid] = message
                            startOperation(channel!!, message.request)
                        }
                        is StopOperation<*> -> {
                            activeMessages.remove(message.request.requestUuid)
                            stopOperation(channel!!, message.request)
                        }
                        is Dispose -> {
                            closeTransport()
                            // Exit the loop and the coroutine scope
                            return
                        }
                    }

                    idleJob = if (activeMessages.isEmpty()) {
                        scope.launch {
                            delay(idleTimeoutMillis)
                            closeTransport()
                        }
                    } else {
                        idleJob?.cancel()
                        null
                    }
                }
            }
        }
    }

    override fun <D : Operation.Data> execute(
        request: ApolloRequest<D>,
    ): Flow<ApolloResponse<D>> {
        return events.onSubscription {
            messages.send(StartOperation(request))
        }.filter {
            it.id == request.requestUuid.toString() || it.id == null
        }.transformWhile {
            when (it) {
                is OperationComplete -> {
                    false
                }
                is NetworkError -> {
                    emit(it)
                    false
                }
                is GeneralError -> {
                    println("Received general error while executing operation ${request.operation.name()}: ${it.payload}")
                    true
                }
                else -> {
                    emit(it)
                    true
                }
            }
        }.map {
            when (it) {
                is OperationResponse -> request.operation
                    .parseJsonResponse(it.payload.jsonReader(), request.executionContext[CustomScalarAdapters]!!)
                    .newBuilder()
                    .requestUuid(request.requestUuid)
                    .build()
                is OperationError -> throw ApolloNetworkException("Operation error ${request.operation.name()}: ${it.payload}")
                is NetworkError -> throw ApolloNetworkException("Network error while executing ${request.operation.name()}", it.cause)

                // Cannot happen as these events are filtered out upstream
                is OperationComplete, is GeneralError -> error("Unexpected event $it")
            }
        }.onCompletion {
            messages.send(StopOperation(request))
        }
    }

    override fun dispose() {
        messages.trySend(Dispose)
    }

    class Builder {
        private var serverUrl: String? = null
        private var topic: String? = null
        private var params: Payload? = null
        private var channelJoinTimeoutMs: Long? = null
        private var idleTimeoutMillis: Long? = null
        private var reconnectWhen: ((Throwable) -> Boolean)? = null

        /**
         * Set the server url
         *
         * @param serverUrl the url to use to establish the socket connection.
         */
        fun serverUrl(serverUrl: String) = apply {
            this.serverUrl = serverUrl
        }

        /**
         * Set the channel topic
         *
         * @param topic the topic to which the channel will subscribe
         */
        fun topic(topic: String) = apply {
            this.topic = topic
        }

        /**
         * Set the socket parameters
         *
         * @param params the parameters to send to the server (e.g. authentication headers)
         */
        fun params(params: Payload) = apply {
            this.params = params
        }

        /**
         * Set the channel join timeout
         *
         * @param channelJoinTimeoutMs the time during which the channel will try to join before timing out
         */
        fun channelJoinTimeoutMs(channelJoinTimeoutMs: Long) = apply {
            this.channelJoinTimeoutMs = channelJoinTimeoutMs
        }

        /**
         * Set the idle timeout
         *
         * @param idleTimeoutMillis the idle time duration beyond which the socket will disconnect
         */
        fun idleTimeoutMillis(idleTimeoutMillis: Long) = apply {
            this.idleTimeoutMillis = idleTimeoutMillis
        }

        /**
         * Configure the [PhoenixNetworkTransport] to reconnect the socket automatically when a network error
         * happens
         *
         * @param reconnectWhen a function taking the error as a parameter and returning 'true' to reconnect
         * automatically or 'false' to forward the error to all listening [Flow]
         *
         */
        fun reconnectWhen(reconnectWhen: ((Throwable) -> Boolean)?) = apply {
            this.reconnectWhen = reconnectWhen
        }

        fun build(): PhoenixNetworkTransport {
            return PhoenixNetworkTransport(
                serverUrl ?: error("No serverUrl specified"),
                topic ?: error("No topic specified"),
                params,
                channelJoinTimeoutMs ?: 10_000,
                idleTimeoutMillis ?: 60_000,
                reconnectWhen
            )
        }
    }
}