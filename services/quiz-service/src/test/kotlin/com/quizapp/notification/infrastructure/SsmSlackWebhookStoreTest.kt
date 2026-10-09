package com.quizapp.notification.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import java.util.UUID

class SsmSlackWebhookStoreTest {

    private val client = SsmClient.builder().region(Region.AP_NORTHEAST_1).build()

    @Test
    @DisplayName("パラメータの名前は、notification-service と IAM のポリシーが前提にする形になる")
    fun parameterName() {
        val tenantId = UUID.fromString("3f6c1a2e-0000-4000-8000-000000000001")

        assertThat(SsmSlackWebhookStore(client, "/quiz-app/dev").name(tenantId))
            .isEqualTo("/quiz-app/dev/tenants/3f6c1a2e-0000-4000-8000-000000000001/slack-webhook-url")
        assertThat(SsmSlackWebhookStore(client, "/quiz-app/dev/").name(tenantId))
            .isEqualTo("/quiz-app/dev/tenants/3f6c1a2e-0000-4000-8000-000000000001/slack-webhook-url")
    }
}
