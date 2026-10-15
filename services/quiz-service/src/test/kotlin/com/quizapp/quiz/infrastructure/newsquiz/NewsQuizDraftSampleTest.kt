package com.quizapp.quiz.infrastructure.newsquiz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lambda（news-quiz-drafter）との約束の形（ADR-0031）。`docs/news-quizzes/draft.json` の見本を読めること。
 * Lambda の側も、同じ見本の形で書き出すことを確かめる
 */
class NewsQuizDraftSampleTest {

    @Test
    @DisplayName("見本の下書きを読める")
    fun readsSample() {
        val draft = NewsQuizDraft.parse(Files.readString(Path.of("../../docs/news-quizzes/draft.json")))

        assertThat(draft.source.url).startsWith("https://aws.amazon.com/")
        assertThat(draft.quiz.choices).hasSize(4)
        assertThat(draft.quiz.choices.count { it.isCorrect }).isEqualTo(1)
    }
}
