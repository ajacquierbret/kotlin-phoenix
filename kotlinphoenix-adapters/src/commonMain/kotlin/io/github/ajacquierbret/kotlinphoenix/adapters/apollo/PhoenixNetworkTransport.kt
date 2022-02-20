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
internal class OperationComplete(override val id: String?) : PhxEvent
internal class OperationError(override val id: String?, val payload: Map<String, Any?>?) : PhxEvent
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
@Suppress("unused")
class PhoenixNetworkTransport private constructor(
    private val serverUrl: String,
    private val topic: String,
    private var params: Payload? = null,
    private var logger: ((log: Any) -> Unit)?,
    private val channelJoinTimeoutMs: Long = 10_000,
    private val idleTimeoutMillis: Long = 60_000,
    private val reconnectWhen: ((Throwable) -> Boolean)?,
    private val reconnectWith: (() -> Payload?)?,
    private val queueMessages: Boolean = true
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

    private val backgroundDispatcher = BackgroundDispatcher()
    private val coroutineScope = CoroutineScope(backgroundDispatcher.coroutineDispatcher)

    /**
     * No need to lock these variables as they are all accessed from the same thread
     */
    private var idleJob: Job? = null
    private var connectionJob: Job? = null
    private val activeMessages = mutableMapOf<Uuid, StartOperation<*>>()

    private var socket: PhxSocket? = null
    private var channel: PhxChannel? = null

    init {
        coroutineScope.launch {
            supervise(this)
            backgroundDispatcher.dispose()
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

    private suspend fun connectionInit(scope: CoroutineScope) {
        val phxSocket = PhxSocket(url = serverUrl, params = params, scope = scope)
        socket = phxSocket

        phxSocket.logger = {
            logger?.invoke(it)
        }

        val connection = try {
            phxSocket.connect()
        } catch (error: SocketEvent.FailureEvent) {
            // Error opening the socket
            mutableEvents.emit(NetworkError(error.throwable))
            throw error
        }

        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection?.collect {
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

        channel = phxSocket.channel(topic, connection!!)

        try {
            withTimeout(channelJoinTimeoutMs) {
                launch {
                    channel?.join()?.collect {
                        when (it.status) {
                            "ok" -> cancel()
                            "error" -> throw SocketEvent.FailureEvent(Throwable("Connection error while joining phoenix channel : $it"), null)
                            else -> println("Unhandled message status while joining phoenix channel : ${it.status}")
                        }
                    }
                }
            }
        } catch (error: SocketEvent.FailureEvent) {
            // Error initializing the connection
            socket = null
            channel = null
            mutableEvents.emit(NetworkError(error.throwable))
            throw error
        }
    }

    /**
     * This happens:
     * - when this coroutine receives a [Dispose] message
     * - when the idleJob completes
     * - when there is an error reading the socket and this coroutine receives a [NetworkError] message
     */
    private suspend fun closeTransport() {
        connectionJob?.cancel()
        connectionJob = null
        channel?.leave()
        socket?.disconnect()
        channel = null
        socket = null
        idleJob?.cancel()
        idleJob = null
    }

    /**
     * Long-running method that creates/handles the socket lifecycle
     *
     * @param scope the current Coroutine scope.
     *
     * @return [Unit]
     */
    private suspend fun supervise(scope: CoroutineScope) {
        while (true) {

            when (val message = messages.receive()) {
                is PhxEvent -> {
                    if (message is NetworkError) {
                        closeTransport()

                        if (reconnectWhen?.invoke(message.cause) == true) {
                            reconnect(scope, queueMessages)
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

                        try {
                            connectionInit(scope)
                        } catch (e: SocketEvent.FailureEvent) {
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

    /**
     * Reconnects the [PhxSocket] with parameters specified by the [reconnectWith] lambda.
     *
     * @param scope the current Coroutine scope.
     * @param queueMessages a boolean indicating whether or not all already registered subscriptions should try to subscribe again
     *
     * @return [Unit]
     */
    @Suppress("private")
    suspend fun reconnect(
        scope: CoroutineScope,
        queueMessages: Boolean = true,
    ) {
        closeTransport()

        reconnectWith?.let {
            params = it.invoke()
        }

        if (queueMessages) {
            activeMessages.values.forEach {
                // Re-queue all start messages
                // Will reconnect the socket and
                // try to rejoin the channel
                messages.trySend(it)
            }
        } else {
            connectionInit(scope)
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

    @Suppress("unused")
    class Builder {
        private var serverUrl: String? = null
        private var topic: String? = null
        private var params: Payload? = null
        private var logger: ((log: Any) -> Unit)? = null
        private var channelJoinTimeoutMs: Long? = null
        private var idleTimeoutMillis: Long? = null
        private var reconnectWhen: ((Throwable) -> Boolean)? = null
        private var reconnectWith: (() -> Payload?)? = null
        private var queueMessages: Boolean? = null

        /**
         * Set the server url
         *
         * @param serverUrl the url to use to establish the socket connection.
         */
        @Suppress("unused")
        fun serverUrl(serverUrl: String) = apply {
            this.serverUrl = serverUrl
        }

        /**
         * Set the channel topic
         *
         * @param topic the topic to which the channel will subscribe
         */
        @Suppress("unused")
        fun topic(topic: String) = apply {
            this.topic = topic
        }

        /**
         * Set the socket parameters
         *
         * @param params the parameters to send to the server (e.g. authentication headers)
         */
        @Suppress("unused")
        fun params(params: Payload) = apply {
            this.params = params
        }

        @Suppress("unused")
        fun logger(logger: ((log: Any) -> Unit)?) = apply {
            this.logger = logger
        }

        /**
         * Set the channel join timeout
         *
         * @param channelJoinTimeoutMs the time during which the channel will try to join before timing out
         */
        @Suppress("unused")
        fun channelJoinTimeoutMs(channelJoinTimeoutMs: Long) = apply {
            this.channelJoinTimeoutMs = channelJoinTimeoutMs
        }

        /**
         * Set the idle timeout
         *
         * @param idleTimeoutMillis the idle time duration beyond which the socket will disconnect
         */
        @Suppress("unused")
        fun idleTimeoutMillis(idleTimeoutMillis: Long) = apply {
            this.idleTimeoutMillis = idleTimeoutMillis
        }

        /**
         * Configure the [PhoenixNetworkTransport] to reconnect the socket automatically when a network error happens.
         *
         * @param queueMessages a boolean indicating whether or not all already registered subscriptions should try to subscribe again
         * @param reconnectWhen a function taking the error as a parameter and returning 'true' to reconnect
         * automatically or 'false' to forward the error to all listening [Flow]
         *
         */
        @Suppress("unused")
        fun reconnectWhen(queueMessages: Boolean = true, reconnectWhen: ((Throwable) -> Boolean)?) = apply {
            this.reconnectWhen = reconnectWhen
            this.queueMessages = queueMessages
        }

        /**
         * Configure the [reconnectWhen] lambda to apply the returned params to the next socket reconnection.
         * Can be useful in order to reconnect to the server with authorization headers applied
         *
         * @param reconnectWith a function returning a [Payload] that will be passed to the Phoenix Socket as a payload parameter
         */
        @Suppress("unused")
        fun reconnectWith(reconnectWith: (() -> Payload?)?) = apply {
            this.reconnectWith = reconnectWith
        }

        fun build(): PhoenixNetworkTransport {
            return PhoenixNetworkTransport(
                serverUrl = serverUrl ?: error("No serverUrl specified"),
                topic = topic ?: error("No topic specified"),
                params = params,
                logger = logger,
                channelJoinTimeoutMs = channelJoinTimeoutMs ?: 10_000,
                idleTimeoutMillis = idleTimeoutMillis ?: 60_000,
                reconnectWhen = reconnectWhen,
                reconnectWith = reconnectWith,
                queueMessages = queueMessages ?: true
            )
        }
    }
}