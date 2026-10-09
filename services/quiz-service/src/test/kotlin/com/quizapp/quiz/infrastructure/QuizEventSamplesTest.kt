package com.quizapp.quiz.infrastructure

import com.quizapp.events.CategoryRef
import com.quizapp.events.ImportedCount
import com.quizapp.events.QuizCreated
import com.quizapp.events.QuizEvent
import com.quizapp.events.QuizEventStatus
import com.quizapp.events.QuizPublished
import com.quizapp.events.QuizRef
import com.quizapp.events.QuizUnpublished
import com.quizapp.events.QuizUpdated
import com.quizapp.events.QuizzesImported
import com.quizapp.events.TenantRef
import com.quizapp.events.detailType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * 送るイベントの形が、`docs/events/` の見本と同じか（ADR-0022）。
 *
 * quiz-service と notification-service は別々にデプロイされ、送る側と受ける側の版は一時的にずれる。
 * 形を見本に固定し、**送る側は書き出したものが見本と同じであること**を、受ける側は見本を読めることを確かめる。
 * 落ちたら、見本を作り直す前に、受け手が新しい形を読めるかを考える（項目を消す・意味を変えるなら版を上げる）。
 *
 * 作り直すとき:
 * ```
 * UPDATE_EVENT_SAMPLES=true ./gradlew :services:quiz-service:test --tests '*QuizEventSamplesTest'
 * ```
 */
class QuizEventSamplesTest {

    companion object {
        /** テストの作業ディレクトリはモジュール直下（services/quiz-service） */
        private val SAMPLES: Path = Path.of("../../docs/events")

        private val tenant = TenantRef(
            id = UUID.fromString("0d6f1a4e-6b2c-4f5e-9a3d-2c1b0e9f8a70"),
            slug = "demo",
            name = "デモ",
        )
        private val occurredAt = Instant.parse("2026-09-30T09:00:00Z")

        private fun quiz(status: QuizEventStatus) = QuizRef(
            id = UUID.fromString("5b3e8f2a-1c4d-4e6f-8a9b-0c1d2e3f4a5b"),
            status = status,
            category = CategoryRef(id = UUID.fromString("9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4"), name = "AWS / インフラ"),
            question = "VPC エンドポイントを使う目的として、最も適切なものはどれか。",
        )

        private fun eventId(n: Int) = UUID.fromString("00000000-0000-4000-8000-00000000000$n")

        /** イベントごとに 1 つの見本。すべての種類を並べる（足し忘れは [everyEventTypeHasSample] が落とす） */
        @JvmStatic
        fun samples(): List<QuizEvent> = listOf(
            QuizCreated(eventId(1), occurredAt, tenant, quiz(QuizEventStatus.DRAFT)),
            QuizUpdated(eventId(2), occurredAt, tenant, quiz(QuizEventStatus.PUBLISHED)),
            QuizPublished(eventId(3), occurredAt, tenant, quiz(QuizEventStatus.PUBLISHED)),
            QuizUnpublished(eventId(4), occurredAt, tenant, quiz(QuizEventStatus.DRAFT)),
            QuizzesImported(eventId(5), occurredAt, tenant, ImportedCount(total = 12, published = 10)),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("書き出したイベントが、見本と同じ")
    fun matchesSample(event: QuizEvent) {
        val file = SAMPLES.resolve("${event.detailType}.json")
        val written = QuizEventJson.writePretty(event) + "\n"

        if (System.getenv("UPDATE_EVENT_SAMPLES") == "true") {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.writeString(file, written)
            return
        }

        assertThat(file).describedAs("見本がありません。UPDATE_EVENT_SAMPLES=true で作ってください").exists()
        // 項目の並びと字下げは比べない。受け手にとって意味があるのは中身だけ
        assertThat(QuizEventJson.readTree(written))
            .describedAs(
                "イベントの形が %s と違います。受け手が読めるかを確かめてから、UPDATE_EVENT_SAMPLES=true で作り直してください",
                file,
            )
            .isEqualTo(QuizEventJson.readTree(Files.readString(file)))
    }

    @Test
    @DisplayName("すべてのイベントに見本があり、使われていない見本がない")
    fun everyEventTypeHasSample() {
        val types = QuizEvent::class.sealedSubclasses.map { it.simpleName }.toSet()

        assertThat(samples().map { it.detailType }).containsExactlyInAnyOrderElementsOf(types)
        if (Files.exists(SAMPLES)) {
            val files = Files.list(SAMPLES).use { paths ->
                paths.map { it.fileName.toString() }.filter { it.endsWith(".json") }.toList()
            }
            assertThat(files.map { it.removeSuffix(".json") }).containsExactlyInAnyOrderElementsOf(types)
        }
    }

    @Test
    @DisplayName("正解・選択肢・解説は、どのイベントにも載らない")
    fun carriesNoAnswer() {
        samples().forEach { event ->
            assertThat(QuizEventJson.write(event)).doesNotContain("choices", "isCorrect", "explanation")
        }
    }

    @Test
    @DisplayName("問題文の冒頭は 100 文字まで。絵文字の途中で切らない")
    fun excerptCountsCharacters() {
        assertThat(QuizEventOutboxJdbc.excerpt("あ".repeat(100))).hasSize(100)
        assertThat(QuizEventOutboxJdbc.excerpt("あ".repeat(101))).isEqualTo("あ".repeat(100))

        // 絵文字は UTF-16 で 2 単位。100 文字目が絵文字でも、壊れた半分を残さない
        val excerpt = QuizEventOutboxJdbc.excerpt("あ".repeat(99) + "🍣" + "い")
        assertThat(excerpt).isEqualTo("あ".repeat(99) + "🍣")
        assertThat(excerpt.codePointCount(0, excerpt.length)).isEqualTo(100)
    }
}
