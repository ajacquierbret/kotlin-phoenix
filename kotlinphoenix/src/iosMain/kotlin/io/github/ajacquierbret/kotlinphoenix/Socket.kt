package io.github.ajacquierbret.kotlinphoenix

import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSURLComponents

actual typealias URL = NSURLComponents

actual class Socket actual constructor(
    url: String,
    paramsClosure: PayloadClosure,
    vsn: String,
    encode: EncodeClosure,
    decode: DecodeClosure,
    scope: CoroutineScope
): SocketCommon(paramsClosure, vsn, encode, decode, scope) {
    override lateinit var endpoint: String
    override lateinit var endpointUrl: URL

    override val protocol: String
        get() = when (endpointUrl.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> endpointUrl.scheme ?: "ws"
        }

    actual constructor(
        url: String,
        params: Payload?,
        vsn: String,
        encode: EncodeClosure,
        decode: DecodeClosure,
        scope: CoroutineScope
    ) : this(url, { params }, vsn, encode, decode, scope)

    init {
        var mutableUrl = url

        // Ensure that the URL ends with "/websocket"
        if (!mutableUrl.contains("/websocket")) {
            // Do not duplicate '/' in path
            if (mutableUrl.last() != '/') {
                mutableUrl += "/"
            }

            // append "websocket" to the path
            mutableUrl += "websocket"
        }

        // Store the endpoint before changing the protocol
        this.endpoint = mutableUrl

        // Store the URL that will be used to establish a connection. Could potentially be
        // different at the time connect() is called based on a changing params closure.
        this.endpointUrl = DefaultsPlatform.buildEndpointUrl(endpoint, paramsClosure, vsn)
    }
}