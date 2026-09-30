package com.quizapp.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SlackWebhookUrlTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://hooks.slack.com/services/TEXAMPLE/BEXAMPLE/example_token-1",
            "https://hooks.slack.com/triggers/E0000000/0000000000/abc_def-123",
        ],
    )
    @DisplayName("Slack が発行する URL を受け付ける")
    fun acceptsSlackWebhook(url: String) {
        assertThat(SlackWebhookUrl(url).value).isEqualTo(url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "http://hooks.slack.com/services/T0/B0/X",
            "https://hooks.slack.com",
            "https://hooks.slack.com/",
            "https://hooks.slack.com.example.test/services/T0/B0/X",
            "https://hooks.slack.com@example.test/services/T0/B0/X",
            "https://hooks.slack.com:8443/services/T0/B0/X",
            "https://example.test/?next=https://hooks.slack.com/services/T0/B0/X",
            "https://hooks.slack.com/services/T0/B0/X?redirect=https://example.test",
            "https://hooks.slack.com/services/../../example.test",
            "https://hooks.slack.com/services/%2e%2e/X",
            "https://hooks.slack.com/services/T0/B0/X#fragment",
            "https://hooks.slack.com/services/T0/B0/X\n",
            " https://hooks.slack.com/services/T0/B0/X",
            "HTTPS://HOOKS.SLACK.COM/services/T0/B0/X",
        ],
    )
    @DisplayName("hooks.slack.com の下を指さないものは受け付けない")
    fun rejectsOtherUrls(url: String) {
        assertThatThrownBy { SlackWebhookUrl(url) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("長すぎるものは受け付けない")
    fun rejectsTooLong() {
        val url = "https://hooks.slack.com/services/" + "X".repeat(SlackWebhookUrl.MAX_LENGTH)
        assertThatThrownBy { SlackWebhookUrl(url) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("文字列にしても URL が出ない。ログに紛れ込ませない")
    fun doesNotRevealUrl() {
        val url = SlackWebhookUrl("https://hooks.slack.com/services/T0/B0/SECRET")
        assertThat(url.toString()).doesNotContain("SECRET")
        assertThat("$url").doesNotContain("hooks.slack.com")
    }
}
