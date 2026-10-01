package com.quizapp.notification

import com.quizapp.events.QuizCreated
import com.quizapp.events.QuizEventStatus
import com.quizapp.events.QuizzesImported
import com.quizapp.events.detailType
import com.quizapp.notification.support.EventSamples
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

/**
 * 受ける側が、`docs/events/` の見本を読めるか（ADR-0022）。送る側は `QuizEventSamplesTest`（quiz-service）が見る。
 * 2 つのサービスは別々にデプロイされるため、形の互換は見本を介して確かめる
 */
class QuizEventReaderTest {

    companion object {
        @JvmStatic
        fun samples() = EventSamples.names()
    }

    @Test
    @DisplayName("見本は 5 種類のイベントをすべて含む")
    fun samplesCoverAllEvents() {
        assertThat(EventSamples.names())
            .containsExactlyInAnyOrder(
                "QuizCreated",
                "QuizUpdated",
                "QuizPublished",
                "QuizUnpublished",
                "QuizzesImported",
            )
    }

    @ParameterizedTest
    @MethodSource("samples")
    @DisplayName("見本を、その detail-type の型として読める")
    fun readsSample(detailType: String) {
        val event = EventSamples.event(detailType)

        assertThat(event.detailType).isEqualTo(detailType)
        assertThat(event.tenant.slug).isEqualTo("demo")
        assertThat(event.version).isEqualTo(1)
    }

    @Test
    @DisplayName("見本の中身を、そのまま型の項目として読む")
    fun readsFields() {
        val created = EventSamples.event("QuizCreated") as QuizCreated
        assertThat(created.eventId).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"))
        assertThat(created.quiz.status).isEqualTo(QuizEventStatus.DRAFT)
        assertThat(created.quiz.category.name).isEqualTo("AWS / インフラ")

        val imported = EventSamples.event("QuizzesImported") as QuizzesImported
        assertThat(imported.imported.total).isEqualTo(12)
        assertThat(imported.imported.published).isEqualTo(10)
    }

    @Test
    @DisplayName("知らない項目は無視する。送る側は版を上げずに項目を足すことがある")
    fun ignoresUnknownFields() {
        val json = EventSamples.json("QuizPublished")
            .replaceFirst("{", """{ "addedLater": { "nested": true }, """)
            .replace(""""question" :""", """"addedToQuiz" : 1, "question" :""")

        val event = QuizEventReader.readDetail("QuizPublished", json)

        assertThat(event).isEqualTo(EventSamples.event("QuizPublished"))
    }

    @Test
    @DisplayName("知らない種類のイベントは null。失敗にはしない")
    fun unknownDetailType() {
        assertThat(QuizEventReader.readDetail("QuizDeleted", EventSamples.json("QuizPublished"))).isNull()
    }

    @Test
    @DisplayName("読めない版は例外にする。黙って捨てず、DLQ に残して流し直せるようにする")
    fun unsupportedVersion() {
        val json = EventSamples.json("QuizPublished").replace(""""version" : 1""", """"version" : 2""")

        assertThatThrownBy { QuizEventReader.readDetail("QuizPublished", json) }
            .isInstanceOf(UnsupportedEventVersionException::class.java)
    }

    @Test
    @DisplayName("EventBridge が渡す封筒から、detail-type と detail を読む")
    fun readsEnvelope() {
        val envelope = """
            {
              "version": "0",
              "id": "6a7e8feb-b491-4cf7-a9f1-bf3703467718",
              "detail-type": "QuizPublished",
              "source": "quiz-app.quiz-service",
              "account": "123456789012",
              "time": "2026-09-30T09:00:01Z",
              "region": "ap-northeast-1",
              "resources": [],
              "detail": ${EventSamples.json("QuizPublished")}
            }
        """.trimIndent()

        val event = QuizEventReader.read(envelope.byteInputStream())

        assertThat(event).isEqualTo(EventSamples.event("QuizPublished"))
    }
}
