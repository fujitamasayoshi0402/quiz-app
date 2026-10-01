package com.quizapp.support.fake

import com.quizapp.quiz.infrastructure.outbox.OutboxEntry
import com.quizapp.quiz.infrastructure.outbox.OutboxRows
import com.quizapp.quiz.infrastructure.outbox.RelayResult
import java.time.Instant
import java.util.UUID

/**
 * Outbox の行のメモリ実装。拾い直しの単体テストで使う。
 *
 * テナントの分離、ロック（`FOR UPDATE SKIP LOCKED`）は SQL の責務で、`OutboxDeliveryApiTest` が確かめる。
 * ここで模すのは、どの行を拾い、送れたものに印を付け、古いものを消すことだけ。
 */
class InMemoryOutboxRows {
    private val rows = linkedMapOf<UUID, Row>()

    /** 拾い直しを呼んだ回数。DB を使ったかどうかの代わりに見る */
    var relayCalls = 0
        private set

    data class Row(val entry: OutboxEntry, val occurredAt: Instant, val publishedAt: Instant? = null)

    fun add(occurredAt: Instant, publishedAt: Instant? = null): OutboxEntry {
        val entry = OutboxEntry(UUID.randomUUID(), UUID.randomUUID(), "QuizCreated", "{}")
        rows[entry.id] = Row(entry, occurredAt, publishedAt)
        return entry
    }

    fun isPublished(id: UUID): Boolean = rows[id]?.publishedAt != null

    fun exists(id: UUID): Boolean = id in rows

    fun rows(now: () -> Instant): OutboxRows = object : OutboxRows {
        override fun markPublished(tenantId: UUID, ids: Collection<UUID>) {
            ids.forEach { id -> rows.computeIfPresent(id) { _, row -> row.copy(publishedAt = now()) } }
        }

        override fun relay(
            olderThan: Instant,
            limit: Int,
            publishedBefore: Instant,
            send: (List<OutboxEntry>) -> Set<UUID>,
        ): RelayResult {
            relayCalls++
            val entries = rows.values
                .filter { it.publishedAt == null && it.occurredAt.isBefore(olderThan) }
                .sortedBy { it.occurredAt }
                .take(limit)
                .map { it.entry }
            val sent = if (entries.isEmpty()) emptySet() else send(entries)
            markPublished(UUID.randomUUID(), sent)
            val expired = rows.values.filter { it.publishedAt?.isBefore(publishedBefore) == true }.map { it.entry.id }
            expired.forEach(rows::remove)
            val oldest = rows.values.filter { it.publishedAt == null }.minOfOrNull { it.occurredAt }
            return RelayResult(sent.size, entries.size - sent.size, expired.size, oldest)
        }
    }
}
