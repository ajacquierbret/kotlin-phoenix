package io.github.ajacquierbret.kotlinphoenix

import platform.Foundation.*
import platform.darwin.NSObject

actual class WebSocketTransport actual constructor(
    actual val url: URL,
    actual val decode: DecodeClosure
): WebSocketTransportCommon() {
    private var connection: NSURLSessionWebSocketTask? = null

    override fun connect(): SocketFlow {
        readyState = Transport.ReadyState.CONNECTING

        val client = NSURLSession.sessionWithConfiguration(
            configuration = NSURLSessionConfiguration.defaultSessionConfiguration(),
            delegate = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
                override fun URLSession(
                    session: NSURLSession,
                    webSocketTask: NSURLSessionWebSocketTask,
                    didOpenWithProtocol: String?
                ) {
                    readyState = Transport.ReadyState.OPEN
                    _sharedFlow.tryEmit(SocketEvent.OpenEvent)
                }
                override fun URLSession(
                    session: NSURLSession,
                    webSocketTask: NSURLSessionWebSocketTask,
                    didCloseWithCode: NSURLSessionWebSocketCloseCode,
                    reason: NSData?
                ) {
                    println("ON CLOSED: ${didCloseWithCode.toInt()} :: $reason")
                    readyState = Transport.ReadyState.CLOSED
                    _sharedFlow.tryEmit(SocketEvent.CloseEvent(didCloseWithCode.toInt()))
                }
            },
            delegateQueue = NSOperationQueue.currentQueue()
        )
        connection = url.URL?.let { client.webSocketTaskWithURL(it) }

        listenMessages()

        connection?.resume()

        return sharedFlow
    }

    override fun disconnect(code: Int, reason: String?) {
        connection?.cancelWithCloseCode(code.toLong(), null)
        connection = null
    }

    override fun send(data: String) {
        val message = NSURLSessionWebSocketMessage(data)
        connection?.sendMessage(message) { error ->
            error?.let {
                println("SEND ERROR : $data ::: $it")
            }
        }
    }

    private fun listenMessages() {
        connection?.receiveMessageWithCompletionHandler { message, nsError ->
            when {
                nsError != null -> {
                    readyState = Transport.ReadyState.CLOSED
                    _sharedFlow.tryEmit(SocketEvent.FailureEvent(Throwable(nsError.description), null))
                }
                message != null -> {
                    message.string?.let { _sharedFlow.tryEmit(SocketEvent.MessageEvent(decode(it))) }
                }
            }
            listenMessages()
        }
    }
}