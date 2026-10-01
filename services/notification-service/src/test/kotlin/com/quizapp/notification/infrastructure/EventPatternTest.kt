package com.quizapp.notification.infrastructure

import com.quizapp.notification.support.EventSamples
import com.quizapp.notification.support.TestLocalStack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * ルールが通知するものだけを通すか（ADR-0022 の 1）。**何を通知するかは、受け手のルールが決める。**
 *
 * Terraform がルールに使う JSON（modules/notification-service/event-pattern.json）を、そのまま EventBridge（LocalStack）に当てる。
 * 公開中のクイズに関わるものを通知し、下書きの編集や、下書きだけの取り込みは通知しない
 */
class EventPatternTest {

    private val events = TestLocalStack.client(EventBridgeClient.builder())
    private val pattern = Files.readString(
        Path.of("../../infra/terraform/modules/notification-service/event-pattern.json"),
    )

    private fun matches(detailType: String, detail: String, source: String = "quiz-app.quiz-service"): Boolean {
        val event = """
            {
              "id": "6a7e8feb-b491-4cf7-a9f1-bf3703467718",
              "detail-type": "$detailType",
              "source": "$source",
              "account": "123456789012",
              "time": "2026-09-30T09:00:01Z",
              "region": "ap-northeast-1",
              "resources": [],
              "detail": $detail
            }
        """.trimIndent()
        return events.testEventPattern { it.eventPattern(pattern).event(event) }.result()
    }

    private fun sample(detailType: String, status: String? = null, published: Int? = null): String {
        var json = EventSamples.json(detailType)
        if (status != null) json = json.replace(Regex(""""status" : "[A-Z]+""""), """"status" : "$status"""")
        if (published != null) json = json.replace(Regex(""""published" : \d+"""), """"published" : $published""")
        return json
    }

    @ParameterizedTest(name = "{0}（{1}）→ {2}")
    @CsvSource(
        "QuizCreated, PUBLISHED, true",
        "QuizCreated, DRAFT, false",
        "QuizUpdated, PUBLISHED, true",
        "QuizUpdated, DRAFT, false",
        "QuizPublished, PUBLISHED, true",
        "QuizUnpublished, DRAFT, false",
    )
    @DisplayName("公開中のクイズに関わるものだけを通す")
    fun quizEvents(detailType: String, status: String, expected: Boolean) {
        assertThat(matches(detailType, sample(detailType, status = status))).isEqualTo(expected)
    }

    @ParameterizedTest(name = "公開 {0} 件 → {1}")
    @CsvSource("10, true", "1, true", "0, false")
    @DisplayName("一括の取り込みは、公開の状態で取り込んだものがあるときだけ通す")
    fun imported(published: Int, expected: Boolean) {
        assertThat(matches("QuizzesImported", sample("QuizzesImported", published = published))).isEqualTo(expected)
    }

    @ParameterizedTest(name = "source={0}")
    @CsvSource("quiz-app.other-service", "aws.events")
    @DisplayName("quiz-service 以外が送ったものは通さない")
    fun otherSource(source: String) {
        assertThat(matches("QuizPublished", sample("QuizPublished"), source = source)).isFalse()
    }
}
