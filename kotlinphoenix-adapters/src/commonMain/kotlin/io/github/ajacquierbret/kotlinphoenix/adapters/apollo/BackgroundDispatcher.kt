package io.github.ajacquierbret.kotlinphoenix.adapters.apollo

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A coroutine dispatcher that can continue to run in the background. Typically,
 * to handle a WebSocket connection or batched HTTP queries
 *
 * On the JVM, it uses a background thread
 * On native, it uses the main thread
 */
internal expect class BackgroundDispatcher() {
    val coroutineDispatcher: CoroutineDispatcher
    fun dispose()
}