package com.quizapp.quiz.infrastructure.outbox

import java.time.Instant
import java.util.UUID

/** Outbox の 1 行。[payload] は EventBridge の `detail`、[eventType] は `detail-type` にそのまま使う */
data class OutboxEntry(val id: UUID, val tenantId: UUID, val eventType: String, val payload: String)

/**
 * イベントを送る先（ADR-0022）。AWS では EventBridge のカスタムバス。
 *
 * **例外を投げない。** 送れたものの ID を返し、残りは送れなかったものとして扱う。
 * 送れなかったものは Outbox に残り、拾い直し（[OutboxRelay]）がもう一度送る
 */
interface EventBus {
    fun publish(entries: List<OutboxEntry>): Set<UUID>
}

/**
 * Outbox の行の読み書き。送る仕組み（[OutboxPublisher]、[OutboxRelay]）だけが使う。
 * イベントを書くのは、クイズの変更と同じトランザクションの中（`QuizEventOutboxJdbc`）
 */
interface OutboxRows {
    /** 送れた印を付ける。テナントを決めて書くため、行レベルセキュリティの下で別のテナントの行には触れない */
    fun markPublished(tenantId: UUID, ids: Collection<UUID>)

    /**
     * 送れなかったものを拾い直す。**テナントをまたいで読む**（`app.outbox_relay` を立てる）。
     *
     * 1 つのトランザクションの中で、[olderThan] より古い送れていない行を古い順に [limit] 件までロックして [send] に渡し、
     * 送れたものに印を付ける。送れてから [publishedBefore] より前の行は消す。
     * ロックした行は、ほかのタスクの拾い直しが飛ばす（`FOR UPDATE SKIP LOCKED`）
     */
    fun relay(
        olderThan: Instant,
        limit: Int,
        publishedBefore: Instant,
        send: (List<OutboxEntry>) -> Set<UUID>,
    ): RelayResult
}

/** [oldestUnpublished] は、送れていない最も古い行のできた時刻。無ければ null */
data class RelayResult(val sent: Int, val failed: Int, val deleted: Int, val oldestUnpublished: Instant?)
