package com.quizapp.quiz.infrastructure.integrity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** データの整合性を確かめる間隔（DEV-115）。既定値のまま、どの環境でも使う */
@ConfigurationProperties("app.integrity")
data class IntegrityProperties(
    /** 止めると、確かめない。テストで、手で呼ぶために使う */
    val enabled: Boolean = true,
    /** 流すかどうかを見る間隔。流すのは [every] に 1 回 */
    val interval: Duration = Duration.ofMinutes(1),
    /** 確かめる間隔 */
    val every: Duration = Duration.ofDays(1),
    /** 利用者の要求で DB を使ってから、流してよい時間。Aurora が一時停止するまで（dev は 30 分）より短くする */
    val activeWindow: Duration = Duration.ofMinutes(DEFAULT_ACTIVE_WINDOW_MINUTES),
) {
    private companion object {
        const val DEFAULT_ACTIVE_WINDOW_MINUTES = 5L
    }
}
