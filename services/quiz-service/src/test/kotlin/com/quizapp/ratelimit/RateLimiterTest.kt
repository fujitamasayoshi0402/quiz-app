package com.quizapp.ratelimit

import com.quizapp.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * 利用者ごとのトークンバケット（DEV-125）。**1 人が使い切っても、ほかの利用者は使える**ことが要点。
 *
 * 要求の流れの中での振る舞い（429 の形、重い操作、所属を確かめる前に止まる）は `RateLimitApiTest` が見る。
 */
class RateLimiterTest {

    private val clock = MutableClock()
    private val limiter = RateLimiter(RateLimit(capacity = 3, refill = 1, per = Duration.ofSeconds(2)), clock)
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @Test
    @DisplayName("上限まで続けて受け、超えたら次に受け付けられるまでの時間を返す")
    fun burstThenRefuse() {
        repeat(3) { assertThat(limiter.tryAcquire(alice)).isNull() }

        assertThat(limiter.tryAcquire(alice)).isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    @DisplayName("1 人が使い切っても、ほかの利用者は使える")
    fun perUser() {
        repeat(3) { limiter.tryAcquire(alice) }

        assertThat(limiter.tryAcquire(alice)).isNotNull()
        assertThat(limiter.tryAcquire(bob)).isNull()
    }

    @Test
    @DisplayName("時間がたつと戻る。端数も持ち越し、上限より多くはたまらない")
    fun refills() {
        repeat(3) { limiter.tryAcquire(alice) }

        clock.advance(Duration.ofSeconds(1))
        assertThat(limiter.tryAcquire(alice)).isEqualTo(Duration.ofSeconds(1))
        clock.advance(Duration.ofSeconds(1))
        assertThat(limiter.tryAcquire(alice)).isNull()

        clock.advance(Duration.ofHours(1))
        repeat(3) { assertThat(limiter.tryAcquire(alice)).isNull() }
        assertThat(limiter.tryAcquire(alice)).isNotNull()
    }

    @Test
    @DisplayName("満タンに戻った利用者の分は、1 分ごとに片付ける。使い切った利用者の分は残す")
    fun sweepsIdleUsers() {
        limiter.tryAcquire(alice)
        clock.advance(Duration.ofSeconds(SWEEP_SECONDS - 1))
        repeat(3) { limiter.tryAcquire(bob) }
        assertThat(limiter.size()).isEqualTo(2)

        // 前に片付けてから 1 分。alice は満タンに戻っている。bob は 1 秒前に使い切ったばかり
        clock.advance(Duration.ofSeconds(1))
        val carol = UUID.randomUUID()
        limiter.tryAcquire(carol)

        assertThat(limiter.size()).isEqualTo(2)
        assertThat(limiter.tryAcquire(bob)).isNotNull()
    }

    private companion object {
        const val SWEEP_SECONDS = 60L
    }
}
