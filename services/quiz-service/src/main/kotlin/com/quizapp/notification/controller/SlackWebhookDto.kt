package com.quizapp.notification.controller

import com.quizapp.notification.domain.SlackWebhookSetting
import com.quizapp.notification.domain.SlackWebhookUrl
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

data class ConfigureSlackWebhookRequest(
    @field:Size(max = SlackWebhookUrl.MAX_LENGTH, message = "URL は {max} 文字以内で入力してください")
    @field:Pattern(
        regexp = SlackWebhookUrl.PATTERN,
        message = "Slack の Incoming Webhook の URL（https://hooks.slack.com/ で始まるもの）を入力してください",
    )
    val url: String,
) {
    /** URL は secret。要求をログに出しても、中身が残らないようにする */
    override fun toString() = "ConfigureSlackWebhookRequest(url=***)"
}

/** 通知先を設定したかどうか。**URL は返さない。** 設定した人にも、二度と見せない */
data class SlackWebhookResponse(
    val configured: Boolean,
    /** 設定した日時。設定していなければ無い */
    val configuredAt: Instant? = null,
) {
    companion object {
        fun from(setting: SlackWebhookSetting?) = SlackWebhookResponse(setting != null, setting?.configuredAt)
    }
}
