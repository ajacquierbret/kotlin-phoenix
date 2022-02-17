package io.github.ajacquierbret.kotlinphoenix.adapters.apollo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal actual class BackgroundDispatcher actual constructor() {
    private var disposed = false
    private val _dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Suppress("unused")
    actual val coroutineDispatcher: CoroutineDispatcher
        get() = _dispatcher

    @Suppress("unused")
    actual fun dispose() {
        if (!disposed) {
            _dispatcher.close()
        }
    }
}