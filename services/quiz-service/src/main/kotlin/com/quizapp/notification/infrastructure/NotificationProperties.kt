package com.quizapp.notification.infrastructure

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 通知先の置き場所（ADR-0022）。
 *
 * 既定値はローカル（LocalStack）向け。AWS では、アプリのタスク定義の環境変数で上書きする。
 * 解説図（[com.quizapp.quiz.infrastructure.FigureProperties]）と同じく、AWS かどうかはプロファイルで分けない。
 */
@Validated
@ConfigurationProperties("app.notifications")
data class NotificationProperties(
    @field:NotBlank val region: String,
    /** SSM のパラメータの名前の頭（例: `/quiz-app/dev`）。この下の `tenants/{テナントの ID}/slack-webhook-url` に置く */
    @field:Pattern(regexp = "^/[A-Za-z0-9/_.-]+$") val parameterPrefix: String,
    val ssm: Ssm = Ssm(),
) {
    data class Ssm(
        /** SSM の接続先。空なら AWS の SSM。ローカルは LocalStack */
        val endpoint: String = "",
    )
}
