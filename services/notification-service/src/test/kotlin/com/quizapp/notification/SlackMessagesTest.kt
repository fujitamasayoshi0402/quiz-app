package com.quizapp.notification

import com.quizapp.events.QuizPublished
import com.quizapp.notification.support.EventSamples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class SlackMessagesTest {

    companion object {
        @JvmStatic
        fun samples() = EventSamples.names()
    }

    private val messages = SlackMessages("https://dev.example.com")
    private val mapper = JsonMapper.builder().build()

    private fun payload(detailType: String): JsonNode = mapper.readTree(messages.of(EventSamples.event(detailType)))

    /** blocks の mrkdwn の文をつなげたもの */
    private fun JsonNode.blockTexts(): String = get("blocks").joinToString("\n") { block ->
        when (block.get("type").asString()) {
            "section" -> block.get("text").get("text").asString()
            else -> block.get("elements").joinToString(" ") { it.get("text").asString() }
        }
    }

    @Test
    @DisplayName("公開したクイズは、何が起きたか、問題文の冒頭、テナント、カテゴリ、管理画面へのリンクを載せる")
    fun quizMessage() {
        val payload = payload("QuizPublished")
        val texts = payload.blockTexts()

        assertThat(texts).contains("*クイズが公開されました*")
        assertThat(texts).contains("VPC エンドポイントを使う目的として、最も適切なものはどれか。")
        assertThat(texts).contains("デモ ・ AWS / インフラ")
        assertThat(texts).contains(
            "<https://dev.example.com/t/demo/admin/quizzes/5b3e8f2a-1c4d-4e6f-8a9b-0c1d2e3f4a5b|管理画面で開く>",
        )
        // 通知の欄に出る文。blocks を表示できないところでも読める
        assertThat(payload.get("text").asString())
            .isEqualTo("[デモ] クイズが公開されました: VPC エンドポイントを使う目的として、最も適切なものはどれか。")
    }

    @ParameterizedTest
    @MethodSource("samples")
    @DisplayName("どのイベントでも、通知の文と blocks を作れる")
    fun everyEventHasMessage(detailType: String) {
        val payload = payload(detailType)

        assertThat(payload.get("text").asString()).startsWith("[デモ] ")
        assertThat(payload.get("blocks").size()).isGreaterThan(0)
    }

    @Test
    @DisplayName("一括の取り込みは、件数とクイズの一覧へのリンクを載せる")
    fun importedMessage() {
        val texts = payload("QuizzesImported").blockTexts()

        assertThat(texts).contains("クイズを 12 件取り込みました（うち公開 10 件）")
        assertThat(texts).contains("<https://dev.example.com/t/demo/admin/quizzes|管理画面で開く>")
    }

    @Test
    @DisplayName("管理者が書いた文字は、Slack の書式の記号として読ませない（全員への呼び出しやリンクを作らせない）")
    fun escapesUserText() {
        val sample = EventSamples.event("QuizPublished") as QuizPublished
        val event = sample.copy(
            tenant = sample.tenant.copy(name = "A&B"),
            quiz = sample.quiz.copy(question = "<!channel> <https://evil.example|ここ> を開く"),
        )

        val payload = mapper.readTree(messages.of(event))
        val texts = payload.blockTexts()

        assertThat(texts).contains("&lt;!channel&gt; &lt;https://evil.example|ここ&gt; を開く")
        assertThat(texts).contains("A&amp;B")
        assertThat(texts).doesNotContain("<!channel>")
        assertThat(payload.get("text").asString()).doesNotContain("<!channel>")
    }
}
