package com.quizapp.newsquiz.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DocumentsTest {
    @Test
    @DisplayName("道具の入力（Document）と Kotlin の値を行き来できる")
    fun roundTrip() {
        val value = mapOf(
            "question" to "問題",
            "choices" to listOf(mapOf("body" to "選択肢", "isCorrect" to true)),
            "indices" to listOf(0, 2),
            "none" to null,
        )

        assertThat(Documents.valueOf(Documents.of(value))).isEqualTo(value)
    }
}
