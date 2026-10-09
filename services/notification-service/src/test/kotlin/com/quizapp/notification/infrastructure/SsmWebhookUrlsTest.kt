package com.quizapp.notification.infrastructure

import com.quizapp.notification.support.TestLocalStack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterType
import java.util.UUID

class SsmWebhookUrlsTest {

    private val ssm = TestLocalStack.client(SsmClient.builder())
    private val prefix = "/quiz-app/test-" + UUID.randomUUID().toString().take(8)
    private val urls = SsmWebhookUrls(ssm, "$prefix/")

    @Test
    @DisplayName("quiz-service が書いた場所の SecureString を、復号して読む")
    fun readsSecureString() {
        val tenantId = UUID.randomUUID()
        val url = "https://hooks.slack.com/services/TEXAMPLE/BEXAMPLE/example_token-1"
        // quiz-service の SsmSlackWebhookStore と同じ名前
        ssm.putParameter {
            it.name("$prefix/tenants/$tenantId/slack-webhook-url").value(url).type(ParameterType.SECURE_STRING)
        }

        assertThat(urls.find(tenantId)).isEqualTo(url)
    }

    @Test
    @DisplayName("設定のないテナントは null")
    fun notConfigured() {
        assertThat(urls.find(UUID.randomUUID())).isNull()
    }
}
