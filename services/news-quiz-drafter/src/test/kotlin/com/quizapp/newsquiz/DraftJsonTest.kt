package com.quizapp.newsquiz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 受け箱に置く形が、`docs/news-quizzes/draft.json` の見本と同じか（ADR-0031）。
 * quiz-service は、同じ見本を読めることを確かめている（`NewsQuizDraftSampleTest`）
 */
class DraftJsonTest {

    @Test
    @DisplayName("見本と同じ中身を書き出すと、見本と同じ JSON になる")
    fun matchesSample() {
        val sample = DraftJson.readTree(Files.readString(Path.of("../../docs/news-quizzes/draft.json")))
        val source = sample["source"]
        val quiz = sample["quiz"]

        val written = DraftJson.write(
            NewsItem(
                guid = "guid",
                title = source["title"].asString(),
                url = source["url"].asString(),
                description = "",
                publishedAt = Instant.parse(source["publishedAt"].asString()),
            ),
            DraftQuiz(
                question = quiz["question"].asString(),
                choices = quiz["choices"].iterator().asSequence()
                    .map { DraftChoice(it["body"].asString(), it["isCorrect"].asBoolean()) }.toList(),
                explanation = quiz["explanation"].asString(),
            ),
        )

        assertThat(DraftJson.readTree(written)).isEqualTo(sample)
    }

    @Test
    @DisplayName("そのまま公開できる形でなければ、理由を返す")
    fun problems() {
        val ok = FakeQuizWriter.quizFor("n1")

        assertThat(ok.problems()).isEmpty()
        assertThat(ok.copy(choices = ok.choices.take(3)).problems()).contains("選択肢が 4 つではありません")
        assertThat(ok.copy(choices = ok.choices.map { it.copy(isCorrect = false) }).problems())
            .contains("正解が 1 つではありません")
        assertThat(ok.copy(choices = ok.choices.map { it.copy(body = "同じ") }).problems()).contains("同じ選択肢があります")
        assertThat(ok.copy(explanation = " ").problems()).contains("解説が空か、長すぎます")
    }
}
