package com.quizapp.notification.infrastructure

import com.quizapp.notification.domain.SlackWebhookStore
import com.quizapp.notification.domain.SlackWebhookStoreUnavailableException
import com.quizapp.notification.domain.SlackWebhookUrl
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException
import software.amazon.awssdk.services.ssm.model.ParameterTier
import software.amazon.awssdk.services.ssm.model.ParameterType
import java.util.UUID

/**
 * テナントの Webhook の URL を、SSM Parameter Store の SecureString に置く（ADR-0022）。
 *
 * 名前は `{prefix}/tenants/{テナントの ID}/slack-webhook-url`。notification-service は同じ名前で読む。
 * **アプリのロールは、この名前への書き込みと削除だけを持ち、読めない**（Terraform の `modules/quiz-service`）。
 * 暗号化は AWS 管理のキー（aws/ssm）。標準のパラメータは無料で、1 万個まで置ける。
 */
class SsmSlackWebhookStore(private val client: SsmClient, private val prefix: String) : SlackWebhookStore {

    override fun put(tenantId: UUID, url: SlackWebhookUrl) = reaching {
        client.putParameter {
            it.name(name(tenantId))
                .value(url.value)
                .type(ParameterType.SECURE_STRING)
                .tier(ParameterTier.STANDARD)
                .overwrite(true)
        }
    }

    override fun delete(tenantId: UUID) = reaching {
        try {
            client.deleteParameter { it.name(name(tenantId)) }
        } catch (expected: ParameterNotFoundException) {
            // 消したいものが無いのは、消せたのと同じ
        }
    }

    /** SDK の例外は、SDK を知らない呼び出し側に分かる形にする。値（URL）は SDK の例外に含まれない */
    private fun reaching(block: () -> Unit) {
        try {
            block()
        } catch (e: SdkException) {
            throw SlackWebhookStoreUnavailableException(e)
        }
    }

    fun name(tenantId: UUID) = "${prefix.trimEnd('/')}/tenants/$tenantId/slack-webhook-url"
}
