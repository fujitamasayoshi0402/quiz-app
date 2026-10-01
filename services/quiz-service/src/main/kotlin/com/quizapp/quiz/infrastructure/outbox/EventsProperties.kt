package com.quizapp.quiz.infrastructure.outbox

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * イベントの送り先と、拾い直しの間隔（ADR-0022）。
 *
 * 既定値はローカル（LocalStack）向け。AWS では、アプリのタスク定義の環境変数で上書きする。
 * 解説図（`FigureProperties`）と同じく、AWS かどうかはプロファイルで分けない。
 */
@Validated
@ConfigurationProperties("app.events")
data class EventsProperties(
    @field:NotBlank val region: String,
    /** EventBridge のカスタムバスの名前 */
    @field:NotBlank val busName: String,
    /** EventBridge の接続先。空なら AWS の EventBridge。ローカルは LocalStack */
    val endpoint: String = "",
    @field:Valid val relay: Relay = Relay(),
) {
    data class Relay(
        /** 止めると、送れなかったイベントは送り直されない。テストで、拾い直しを手で呼ぶために使う */
        val enabled: Boolean = true,
        /** 拾い直しの間隔 */
        val interval: Duration = Duration.ofMinutes(1),
        /** 利用者の要求で DB を使ってから、拾い直しを続ける時間。過ぎたら DB に触れない */
        val activeWindow: Duration = Duration.ofMinutes(DEFAULT_ACTIVE_WINDOW_MINUTES),
        /** これより新しい行は拾わない。コミットの直後の送信と重ならないようにする */
        val minAge: Duration = Duration.ofMinutes(1),
        /** 送れた行を残す時間。バスのアーカイブ（7 日）と揃える */
        val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
        /** 1 回に拾い直す件数 */
        @field:Positive val batchSize: Int = DEFAULT_BATCH_SIZE,
    ) {
        private companion object {
            const val DEFAULT_ACTIVE_WINDOW_MINUTES = 5L
            const val DEFAULT_RETENTION_DAYS = 7L
            const val DEFAULT_BATCH_SIZE = 100
        }
    }
}
