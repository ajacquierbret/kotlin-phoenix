package io.github.ajacquierbret.kotlinphoenix.client

import okhttp3.*
import java.net.URL

actual class WebSocketTransport actual constructor(
    actual val url: URL,
    actual val decode: DecodeClosure
): WebSocketTransportCommon() {
    private var connection: WebSocket? = null

    override fun connect(): SocketFlow {
        readyState = Transport.ReadyState.CONNECTING

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                readyState = Transport.ReadyState.OPEN
                _sharedFlow.tryEmit(SocketEvent.OpenEvent)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                readyState = Transport.ReadyState.CLOSED

                // Try sending a FailureEvent, to inform of the error
                _sharedFlow.tryEmit(SocketEvent.FailureEvent(t, response))

                /*
                  According to the OkHttp documentation, `onFailure` will be

                  "Invoked when a web socket has been closed due to an error reading from or writing to the
                  network. Both outgoing and incoming messages may have been lost. No further calls to this
                  listener will be made."

                  This means a CloseEvent will never be sent which will never kick off the socket reconnect
                  attempts.

                  The JS WebSocket class calls `onError` and then `onClose` which will then trigger
                  the reconnect logic inside of the PhoenixClient. In order to mimic this behavior and abstract
                  this detail of OkHttp away from the PhoenixClient, the `WebSocketTransport` class should
                  convert `onFailure` calls to a `FailureEvent` and `CloseEvent` sequence.
                 */
                _sharedFlow.tryEmit(SocketEvent.CloseEvent(WS_CLOSE_ABNORMAL))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                _sharedFlow.tryEmit(SocketEvent.MessageEvent(decode(text)))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("ON CLOSING: $code :: $reason")
                readyState = Transport.ReadyState.CLOSING
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("ON CLOSED: $code :: $reason")
                readyState = Transport.ReadyState.CLOSED
                _sharedFlow.tryEmit(SocketEvent.CloseEvent(code))
            }
        }

        val request = Request.Builder().url(url).build()
        connection = OkHttpClient().newWebSocket(request, listener)

        return sharedFlow
    }

    override fun disconnect(code: Int, reason: String?) {
        connection?.close(code, reason)
        connection = null
    }

    override fun send(data: String) {
        connection?.send(data)
    }
}