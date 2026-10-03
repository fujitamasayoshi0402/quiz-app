package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.events.QuizEvent
import com.quizapp.tracing.TraceHeaders
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import java.util.UUID

/**
 * EventBridge のカスタムバスへ送る（ADR-0022）。
 *
 * `PutEvents` は 1 回 10 件まで。**一部だけが失敗することがある**（応答は成功のまま、失敗した項目だけが `errorCode` を持つ）。
 * 応答の項目は要求と同じ並びなので、位置で行と対応させる。
 *
 * 書いたときのトレースの文脈を、X-Ray のトレースヘッダ（`TraceHeader`）として渡す（ADR-0026）。
 * EventBridge が受け手の Lambda に渡し、Lambda の区間が、イベントを書いた要求のトレースにつながる
 */
class EventBridgeEventBus(private val client: EventBridgeClient, private val busName: String) : EventBus {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(entries: List<OutboxEntry>): Set<UUID> =
        entries.chunked(MAX_ENTRIES_PER_REQUEST).flatMapTo(mutableSetOf()) { putEvents(it) }

    private fun putEvents(batch: List<OutboxEntry>): Set<UUID> {
        val request = PutEventsRequest.builder().entries(batch.map(::toRequestEntry)).build()
        val response = try {
            client.putEvents(request)
        } catch (e: SdkException) {
            log.warn("イベントを送れませんでした。あとで拾い直します: {} 件, {}", batch.size, e.message)
            return emptySet()
        }

        val results = batch.zip(response.entries())
        results.filter { (_, result) -> result.errorCode() != null }.forEach { (entry, result) ->
            log.warn("イベントを送れませんでした。あとで拾い直します: {} {} {}", entry.id, result.errorCode(), result.errorMessage())
        }
        return results.filter { (_, result) -> result.errorCode() == null }.map { (entry, _) -> entry.id }.toSet()
    }

    private fun toRequestEntry(entry: OutboxEntry): PutEventsRequestEntry = PutEventsRequestEntry.builder()
        .eventBusName(busName)
        .source(QuizEvent.SOURCE)
        .detailType(entry.eventType)
        .detail(entry.payload)
        .traceHeader(entry.traceParent?.let(TraceHeaders::xrayTraceHeader))
        .build()

    companion object {
        /** `PutEvents` の 1 回の上限 */
        const val MAX_ENTRIES_PER_REQUEST = 10
    }
}
