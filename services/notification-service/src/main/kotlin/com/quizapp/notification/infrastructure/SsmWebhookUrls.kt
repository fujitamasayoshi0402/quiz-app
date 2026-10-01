package com.quizapp.notification.infrastructure

import com.quizapp.notification.WebhookUrls
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException
import java.util.UUID

/**
 * SSM Parameter Store の SecureString から、テナントの Webhook の URL を読む（ADR-0022 の 5）。
 * quiz-service が書く場所（`SsmSlackWebhookStore`）と同じ名前を使う。
 * AWS 管理のキー（aws/ssm）で暗号化しているため、kms:Decrypt の権限は要らない
 *
 * @param prefix パラメータの頭（例: `/quiz-app/dev`）
 */
class SsmWebhookUrls(private val ssm: SsmClient, private val prefix: String) : WebhookUrls {
    override fun find(tenantId: UUID): String? = try {
        ssm.getParameter { it.name(parameterName(tenantId)).withDecryption(true) }.parameter().value()
    } catch (_: ParameterNotFoundException) {
        null
    }

    fun parameterName(tenantId: UUID) = "${prefix.trimEnd('/')}/tenants/$tenantId/slack-webhook-url"
}
