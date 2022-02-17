package io.github.ajacquierbret.kotlinphoenix.adapters.apollo

import kotlinx.coroutines.*
import platform.Foundation.NSThread
import platform.darwin.*
import kotlin.coroutines.CoroutineContext

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
internal actual class BackgroundDispatcher actual constructor() {
    init {
        check(NSThread.isMainThread) {
            "BackgroundDispatcher must be called from the main thread"
        }
    }

    @Suppress("unused")
    actual val coroutineDispatcher: CoroutineDispatcher
        get() = DefaultDispatcher

    @Suppress("unused")
    actual fun dispose() {
    }
}

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
private object DefaultDispatcher: CoroutineDispatcher(), Delay {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatch_get_main_queue()) {
            block.run()
        }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, timeMillis * 1_000_000), dispatch_get_main_queue()) {
            with(continuation) {
                resumeUndispatched(Unit)
            }
        }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val handle = object : DisposableHandle {
            var disposed = false
                private set

            override fun dispose() {
                disposed = true
            }
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, timeMillis * 1_000_000), dispatch_get_main_queue()) {
            if (!handle.disposed) {
                block.run()
            }
        }

        return handle
    }
}