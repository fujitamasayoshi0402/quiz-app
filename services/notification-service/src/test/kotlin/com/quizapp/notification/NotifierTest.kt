package com.quizapp.notification

import com.quizapp.notification.support.EventSamples
import com.quizapp.notification.support.InMemoryDeliveries
import com.quizapp.notification.support.InMemoryWebhookUrls
import com.quizapp.notification.support.RecordingSlack
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.http.HttpTimeoutException

class NotifierTest {

    private val event = EventSamples.event("QuizPublished")
    private val webhookUrl = "https://hooks.slack.com/services/TEXAMPLE/BEXAMPLE/example_token-1"
    private val configured = InMemoryWebhookUrls(mapOf(event.tenant.id to webhookUrl))
    private val deliveries = InMemoryDeliveries()
    private val logs = mutableListOf<String>()

    private fun notifier(slack: RecordingSlack, webhookUrls: WebhookUrls = configured) =
        Notifier(webhookUrls, deliveries, slack, SlackMessages("https://dev.example.com"), logs::add)

    @Test
    @DisplayName("テナントの Webhook に送り、済みにする")
    fun delivers() {
        val slack = RecordingSlack()

        notifier(slack).notify(event)

        assertThat(slack.posted).hasSize(1)
        assertThat(slack.posted.single().first).isEqualTo(webhookUrl)
        assertThat(deliveries.records[event.eventId]).isEqualTo("DONE")
    }

    @Test
    @DisplayName("通知先を設定していないテナントでは、何もしない")
    fun notConfigured() {
        val slack = RecordingSlack()

        notifier(slack, InMemoryWebhookUrls()).notify(event)

        assertThat(slack.posted).isEmpty()
        assertThat(deliveries.records).isEmpty()
    }

    @Test
    @DisplayName("Slack の Webhook ではない URL には送らない")
    fun rejectsOtherUrl() {
        val slack = RecordingSlack()
        val elsewhere = InMemoryWebhookUrls(mapOf(event.tenant.id to "https://evil.example/hook"))

        notifier(slack, elsewhere).notify(event)

        assertThat(slack.posted).isEmpty()
        assertThat(deliveries.records).isEmpty()
    }

    @Test
    @DisplayName("同じイベントが 2 度届いても、知らせるのは 1 度だけ")
    fun ignoresDuplicate() {
        val slack = RecordingSlack()

        notifier(slack).notify(event)
        notifier(slack).notify(event)

        assertThat(slack.posted).hasSize(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [429, 500, 503])
    @DisplayName("429 と 5xx は、記録を消して例外で終わる。再試行で送り直せる")
    fun retriesLater(status: Int) {
        val failing = RecordingSlack(SlackResponse(status, "service_unavailable"))

        assertThatThrownBy { notifier(failing).notify(event) }.isInstanceOf(SlackUnavailableException::class.java)
        assertThat(deliveries.records).isEmpty()

        val recovered = RecordingSlack()
        notifier(recovered).notify(event)
        assertThat(recovered.posted).hasSize(1)
    }

    @Test
    @DisplayName("通信の失敗は、記録を消して例外で終わる。例外に URL を載せない")
    fun networkFailure() {
        val failing = RecordingSlack(failure = HttpTimeoutException("request to $webhookUrl timed out"))

        assertThatThrownBy { notifier(failing).notify(event) }
            .isInstanceOf(SlackUnavailableException::class.java)
            .hasNoCause()
            .message().doesNotContain("hooks.slack.com")
        assertThat(deliveries.records).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404, 410])
    @DisplayName("ほかの 4xx は再試行しても直らない。ログに残して済みにする")
    fun givesUpOnClientError(status: Int) {
        val rejecting = RecordingSlack(SlackResponse(status, "no_service"))

        notifier(rejecting).notify(event)

        assertThat(deliveries.records[event.eventId]).isEqualTo("DONE")
        assertThat(logs.last()).contains("$status").contains("no_service")
    }

    @Test
    @DisplayName("ログに Webhook の URL を出さない")
    fun neverLogsUrl() {
        notifier(RecordingSlack()).notify(event)
        notifier(RecordingSlack()).notify(event)
        notifier(RecordingSlack(SlackResponse(404, "no_service"))).notify(EventSamples.event("QuizCreated"))
        notifier(RecordingSlack(), InMemoryWebhookUrls(mapOf(event.tenant.id to "https://evil.example/x"))).notify(
            EventSamples.event("QuizUpdated"),
        )

        assertThat(logs).isNotEmpty()
        assertThat(logs).noneMatch { it.contains("hooks.slack.com") || it.contains("evil.example") }
    }
}
