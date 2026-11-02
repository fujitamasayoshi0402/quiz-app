package com.quizapp.warmup

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataAccessException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 止まっている Aurora を、利用者が使う前に起こす（DEV-195、DEV-217）。web のトップ（「デモとして入る」の入口）を表示したときに呼ばれる。
 *
 * Aurora は使われないと一時停止し（min 0 ACU）、復帰に 15〜20 秒かかる。入口を読んでいる間に復帰を進めておく。
 *
 * **起こす回数に、1 日（JST）の上限を付ける。** 1 回起こすと Aurora は 15 分ほど起きている。
 * スクリプトを動かすクローラーが入口を開くたびに起こすと、一時停止しなくなる。上限を超えたら、DB に触れずに返す
 *
 * - DB を使ってから [WarmUpProperties.awakeWindow] の間は、起きているとみなし、起こさない。回数にも数えない
 * - 回数はタスクのメモリで数える。prod のタスクは 1 つなので、ほぼ全体の上限になる。入れ替えたときは 0 から数え直す
 * - **利用者の要求として数えない（[DatabaseActivity] に記録しない）。** 記録すると、押さずに帰った人の分も拾い直しが動く
 */
class DatabaseWarmUp(
    private val touchDatabase: () -> Unit,
    private val activity: DatabaseActivity,
    private val properties: WarmUpProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var day: LocalDate? = null
    private var count = 0
    private var lastWarmedAt: Instant? = null

    fun warmUp(): WarmUpResult {
        val result = decide()
        if (result != WarmUpResult.WARMED) return result
        try {
            touchDatabase()
        } catch (e: DataAccessException) {
            // 復帰が接続の待ち時間（45 秒）に間に合わなかったなど。利用者が使うときの要求が、もう一度起こす
            log.warn("DB を先に起こせませんでした: {}", e.javaClass.simpleName)
        }
        return result
    }

    /** 起こすかどうかを決め、起こすなら数える。DB に触れている間は、ほかの要求を待たせない */
    @Synchronized
    private fun decide(): WarmUpResult {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZONE)
        if (today != day) {
            day = today
            count = 0
        }
        val result = when {
            awake(now) -> WarmUpResult.AWAKE
            count >= properties.dailyLimit -> WarmUpResult.LIMIT_REACHED
            else -> WarmUpResult.WARMED
        }
        if (result == WarmUpResult.WARMED) {
            count++
            lastWarmedAt = now
            log.info("DB を先に起こします（今日 {} 回目、上限 {} 回）", count, properties.dailyLimit)
            if (count == properties.dailyLimit) log.info("今日の DB を先に起こす回数が上限に達しました。明日まで起こしません")
        }
        return result
    }

    private fun awake(now: Instant): Boolean = activity.usedWithin(properties.awakeWindow) ||
        lastWarmedAt?.isAfter(now.minus(properties.awakeWindow)) == true

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}

enum class WarmUpResult {
    /** 起こした（すでに起きていたかもしれない） */
    WARMED,

    /** 少し前に DB を使ったか起こしたので、起きているとみなした */
    AWAKE,

    /** 今日の上限に達した */
    LIMIT_REACHED,
}

/** 上限の値。既定値をどの環境でも使う */
@ConfigurationProperties("app.warm-up")
data class WarmUpProperties(
    /** 1 回で 15 分ほど（0.5 ACU で約 0.02 ドル）。上限まで起こしても、1 日 0.4 ドルほど */
    val dailyLimit: Int = 20,
    /** Aurora が一時停止するまでの時間（prod 15 分、dev 30 分）より短くする。長いと、止まったあとも起きているとみなす */
    val awakeWindow: Duration = Duration.ofMinutes(AWAKE_WINDOW_MINUTES),
)

private const val AWAKE_WINDOW_MINUTES = 10L
