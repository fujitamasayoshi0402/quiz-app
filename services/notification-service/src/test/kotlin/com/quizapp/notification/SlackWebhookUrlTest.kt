package com.quizapp.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 送る前にも確かめる。保存するときの規則（quiz-service の `SlackWebhookUrl`）と同じ */
class SlackWebhookUrlTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://hooks.slack.com/services/TEXAMPLE/BEXAMPLE/example_token-1",
            "https://hooks.slack.com/workflows/TEXAMPLE/AEXAMPLE/123/example",
        ],
    )
    fun accepts(url: String) {
        assertThat(SlackWebhookUrl.isValid(url)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://hooks.slack.com/services/T/B/x",
            "https://hooks.slack.com.evil.example/services/T/B/x",
            "https://evil.example/https://hooks.slack.com/services/T/B/x",
            "https://hooks.slack.com@evil.example/services/T/B/x",
            "https://hooks.slack.com/services/T/B/x?redirect=https://evil.example",
            "https://hooks.slack.com/services/../../x",
            "https://hooks.slack.com/",
            "",
        ],
    )
    fun rejects(url: String) {
        assertThat(SlackWebhookUrl.isValid(url)).isFalse()
    }

    @Test
    fun rejectsTooLong() {
        assertThat(SlackWebhookUrl.isValid("https://hooks.slack.com/" + "a".repeat(500))).isFalse()
    }
}
