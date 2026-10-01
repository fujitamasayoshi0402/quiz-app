package com.quizapp.support

import java.time.Duration

/**
 * 別のスレッドで進むもの（コミットの直後の送信）を待って確かめる。
 * [assertion] が通るまで繰り返し、[timeout] を過ぎたら最後の失敗をそのまま投げる
 */
fun eventually(timeout: Duration = Duration.ofSeconds(5), assertion: () -> Unit) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        try {
            return assertion()
        } catch (e: AssertionError) {
            if (System.nanoTime() > deadline) throw e
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }
}

private const val POLL_INTERVAL_MILLIS = 50L
