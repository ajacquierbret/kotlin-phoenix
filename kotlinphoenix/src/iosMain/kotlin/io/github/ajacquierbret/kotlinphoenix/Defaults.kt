package io.github.ajacquierbret.kotlinphoenix

import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

actual object DefaultsPlatform {
    internal actual fun buildEndpointUrl(
        endpoint: String,
        paramsClosure: PayloadClosure,
        vsn: String
    ): URL {
        val httpUrlComponents = NSURLComponents(endpoint)

        // Add the VSN query parameter
        httpUrlComponents.queryItems = listOf(NSURLQueryItem(name = "vsn", value = vsn))

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