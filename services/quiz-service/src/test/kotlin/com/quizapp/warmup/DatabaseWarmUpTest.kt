package com.quizapp.warmup

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import com.quizapp.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import java.time.Duration
import java.time.Instant

/**
 * DB を先に起こす（DEV-217）。**上限を超えたら DB に触れない**ことが要点。
 * 触れると、クローラーが入口を開くたびに Aurora が起き、一時停止しなくなる。
 */
class DatabaseWarmUpTest {

    // 2026-10-01 08:00 JST
    private val clock = MutableClock(Instant.parse("2026-09-30T23:00:00Z"))
    private val activity = DatabaseActivity(clock)
    private val properties = WarmUpProperties(dailyLimit = 3, awakeWindow = Duration.ofMinutes(10))
    private var touches = 0
    private val warmUp = DatabaseWarmUp({ touches++ }, activity, properties, clock)

    /** 起きているとみなす時間を空けて、もう一度呼ぶ */
    private fun warmUpLater(): WarmUpResult {
        clock.advance(properties.awakeWindow)
        return warmUp.warmUp()
    }

    @Test
    @DisplayName("止まっていそうなら、DB に触れて起こす")
    fun touchesDatabase() {
        assertThat(warmUp.warmUp()).isEqualTo(WarmUpResult.WARMED)

        assertThat(touches).isOne()
    }

    @Test
    @DisplayName("1 日の上限を超えたら、DB に触れない")
    fun stopsAtDailyLimit() {
        repeat(3) { assertThat(warmUpLater()).isEqualTo(WarmUpResult.WARMED) }

        assertThat(warmUpLater()).isEqualTo(WarmUpResult.LIMIT_REACHED)
        assertThat(touches).isEqualTo(3)
    }

    @Test
    @DisplayName("日付（JST）が変わったら、数え直す")
    fun resetsOnNextDayInTokyo() {
        repeat(3) { warmUpLater() }
        assertThat(warmUpLater()).isEqualTo(WarmUpResult.LIMIT_REACHED)

        // 08:40 JST から 15 時間 20 分で、翌日の 00:00 JST
        clock.advance(Duration.ofHours(15).plusMinutes(20))
        assertThat(warmUp.warmUp()).isEqualTo(WarmUpResult.WARMED)
        assertThat(touches).isEqualTo(4)
    }

    @Test
    @DisplayName("起こしてから少しの間は、起こさず、回数にも数えない")
    fun skipsWhileRecentlyWarmed() {
        warmUp.warmUp()

        clock.advance(properties.awakeWindow.minusSeconds(1))
        assertThat(warmUp.warmUp()).isEqualTo(WarmUpResult.AWAKE)
        assertThat(touches).isOne()

        // 間に数えていれば、ここで上限（3 回）に当たる
        repeat(2) { assertThat(warmUpLater()).isEqualTo(WarmUpResult.WARMED) }
    }

    @Test
    @DisplayName("利用者が DB を使ってから少しの間は、起こさない")
    fun skipsWhileUsedByUsers() {
        activity.record()

        clock.advance(properties.awakeWindow.minusSeconds(1))
        assertThat(warmUp.warmUp()).isEqualTo(WarmUpResult.AWAKE)

        clock.advance(Duration.ofMinutes(1))
        assertThat(warmUp.warmUp()).isEqualTo(WarmUpResult.WARMED)
        assertThat(touches).isOne()
    }

    @Test
    @DisplayName("起こしても、利用者が使ったことにはしない（拾い直しを動かさない）")
    fun doesNotRecordActivity() {
        warmUp.warmUp()

        assertThat(activity.usedWithin(Duration.ofDays(1))).isFalse()
    }

    @Test
    @DisplayName("DB に届かなくても、例外を投げない")
    fun swallowsDatabaseFailure() {
        val failing = DatabaseWarmUp({ throw QueryTimeoutException("復帰の待ちの時間切れ") }, activity, properties, clock)

        assertThat(failing.warmUp()).isEqualTo(WarmUpResult.WARMED)
    }
}
