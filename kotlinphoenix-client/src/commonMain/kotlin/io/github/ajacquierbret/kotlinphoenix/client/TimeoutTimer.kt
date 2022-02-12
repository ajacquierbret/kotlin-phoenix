/*
 * Copyright (c) 2019 Daniel Rees <daniel.rees18@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.github.ajacquierbret.kotlinphoenix.client

import kotlinx.coroutines.*

/**
 * A Timer class that schedules a [Job] to be called in the future. Can be configured
 * to use a custom retry pattern, such as exponential backoff.
 */
class TimeoutTimer(
  private val timerCalculation: (tries: Int) -> Long,
  private val scope: CoroutineScope
) {

  /** How many tries the Timer has attempted */
  private var tries: Int = 0

  /** The job that has been scheduled to be executed  */
  private var job: Job? = null

  /**
   * Resets the Timer, clearing the number of current tries and stops
   * any scheduled timeouts.
   */
  fun reset() {
    tries = 0
    clearTimer()
  }

  /** Cancels any previous timeouts and scheduled a new one */
  fun scheduleTimeout(block: suspend CoroutineScope.() -> Unit) {
    clearTimer()

    // Schedule a task to be performed after the calculated timeout in milliseconds
    val timeout = timerCalculation(tries + 1)
    job = scope.launch {
        delay(timeout)
        tries += 1
        block(this)
        if (isActive) cancel()
    }
  }

  //------------------------------------------------------------------------------
  // Private
  //------------------------------------------------------------------------------
  private fun clearTimer() {
    // Cancel the job from completing
    job?.cancel()
    job = null
  }
}