package com.quizapp.quiz.infrastructure.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry
import java.util.UUID

/**
 * EventBridge へ送るときの、件数の上限と一部の失敗の扱い。
 * 実際の EventBridge（LocalStack）に届くことは `EventBridgeEventBusLocalStackTest` が見る。
 */
class EventBridgeEventBusTest {

    /** 受け取った要求を残し、[failing] の本文（detail）のものだけを失敗として返す */
    private class FakeEventBridge : EventBridgeClient {
        val requests = mutableListOf<PutEventsRequest>()
        val failing = mutableSetOf<String>()
        var unreachable = false

        override fun putEvents(request: PutEventsRequest): PutEventsResponse {
            if (unreachable) throw SdkClientException.create("接続できません")
            requests += request
            val results = request.entries().map { entry ->
                if (entry.detail() in failing) {
                    PutEventsResultEntry.builder().errorCode("InternalFailure").errorMessage("失敗").build()
                } else {
                    PutEventsResultEntry.builder().eventId(UUID.randomUUID().toString()).build()
                }
            }
            return PutEventsResponse.builder()
                .failedEntryCount(results.count { it.errorCode() != null })
                .entries(results)
                .build()
        }

        override fun serviceName(): String = "events"

        override fun close() = Unit
    }

    private val client = FakeEventBridge()
    private val bus = EventBridgeEventBus(client, "quiz-app-test")

    private fun entries(count: Int) = (1..count).map {
        OutboxEntry(UUID.randomUUID(), UUID.randomUUID(), "QuizCreated", """{"n":$it}""")
    }

    @Test
    @DisplayName("バス・source・detail-type・detail を付けて送る")
    fun sendsEnvelope() {
        val entry = entries(1).single()

        assertThat(bus.publish(listOf(entry))).containsExactly(entry.id)

        val sent = client.requests.single().entries().single()
        assertThat(sent.eventBusName()).isEqualTo("quiz-app-test")
        assertThat(sent.source()).isEqualTo("quiz-app.quiz-service")
        assertThat(sent.detailType()).isEqualTo("QuizCreated")
        assertThat(sent.detail()).isEqualTo(entry.payload)
    }

    @Test
    @DisplayName("書いたときのトレースの文脈を、X-Ray のトレースヘッダにして渡す。無ければ付けない")
    fun passesTraceHeader() {
        val traced = entries(1).single().copy(traceParent = "00-6a1f3c2b0123456789abcdef01234567-0123456789abcdef-01")
        val untraced = entries(1).single()

        bus.publish(listOf(traced, untraced))

        val sent = client.requests.single().entries()
        assertThat(
            sent[0].traceHeader(),
        ).isEqualTo("Root=1-6a1f3c2b-0123456789abcdef01234567;Parent=0123456789abcdef;Sampled=1")
        assertThat(sent[1].traceHeader()).isNull()
    }

    @Test
    @DisplayName("1 回に送るのは 10 件まで。超えた分は分けて送る")
    fun splitsIntoBatchesOfTen() {
        val all = entries(23)

        assertThat(bus.publish(all)).containsExactlyInAnyOrderElementsOf(all.map { it.id })
        assertThat(client.requests.map { it.entries().size }).containsExactly(10, 10, 3)
    }

    @Test
    @DisplayName("一部だけ失敗したら、送れたものだけを返す")
    fun partialFailure() {
        val all = entries(3)
        client.failing += all[1].payload

        assertThat(bus.publish(all)).containsExactlyInAnyOrder(all[0].id, all[2].id)
    }

    @Test
    @DisplayName("届かなければ、例外にせず何も送れなかったことにする")
    fun unreachable() {
        client.unreachable = true

        assertThat(bus.publish(entries(2))).isEmpty()
    }
}
