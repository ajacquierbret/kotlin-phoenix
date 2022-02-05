package io.github.ajacquierbret.kotlinphoenix

import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

actual object DefaultsPlatform {
    internal actual fun buildEndpointUrl(
        endpoint: String,
        paramsClosure: PayloadClosure,
        vsn: String
    ): URL {
        var mutableUrl = endpoint
        // Silently replace web socket URLs with HTTP URLs.
        if (endpoint.regionMatches(0, "ws:", 0, 3, ignoreCase = true)) {
            mutableUrl = "http:" + endpoint.substring(3)
        } else if (endpoint.regionMatches(0, "wss:", 0, 4, ignoreCase = true)) {
            mutableUrl = "https:" + endpoint.substring(4)
        }

        // Add the VSN query parameter
        val httpUrlComponents = NSURLComponents(mutableUrl)
        httpUrlComponents.queryItems = httpUrlComponents.queryItems?.plus(NSURLQueryItem(name = "vsn", value = vsn))

        // Append any additional query params
        paramsClosure.invoke()?.let {
            it.forEach { (key, value) ->
                httpUrlComponents.queryItems = httpUrlComponents.queryItems?.plus(NSURLQueryItem(name = key, value = value.toString()))
            }
        }

        // Return the [URL] that will be used to establish a connection
        return httpUrlComponents
    }
}