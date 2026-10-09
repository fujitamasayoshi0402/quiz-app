package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.tenant.TenantSession
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `quiz.outbox` を、送る仕組みのために読み書きする（ADR-0022）。
 *
 * 送る仕組みは利用者の要求の外（別のスレッド、定期的な処理）で動くため、テナントの文脈（`TenantContext`）を持たない。
 * トランザクションはここで張る
 */
@Component
class OutboxRowsJdbc(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantSession: TenantSession,
) : OutboxRows {

    override fun markPublished(tenantId: UUID, ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        transactionTemplate.executeWithoutResult {
            tenantSession.apply(tenantId)
            update(ids)
        }
    }

    override fun relay(
        olderThan: Instant,
        limit: Int,
        publishedBefore: Instant,
        send: (List<OutboxEntry>) -> Set<UUID>,
    ): RelayResult = requireNotNull(
        transactionTemplate.execute {
            // テナントをまたいで読む印（V17 のポリシー）。SET LOCAL 相当で、トランザクションの終わりで消える。
            // **立てるのはここだけ。** ほかのコードが絞り込みを書き漏らしても、別のテナントの行は返らない
            jdbcTemplate.queryForObject("SELECT set_config('app.outbox_relay', 'on', true)", String::class.java)

            // 送っている間もロックを持ち続ける。デプロイの入れ替え中にタスクが 2 つあっても、同じ行を送らない
            val entries = jdbcTemplate.query(
                """
                SELECT id, tenant_id, event_type, payload::text AS payload, trace_parent FROM quiz.outbox
                WHERE published_at IS NULL AND occurred_at < ?
                ORDER BY occurred_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                ENTRY,
                olderThan.atOffset(ZoneOffset.UTC),
                limit,
            )
            val sent = if (entries.isEmpty()) emptySet() else send(entries)
            update(sent)

            val deleted = jdbcTemplate.update(
                "DELETE FROM quiz.outbox WHERE published_at < ?",
                publishedBefore.atOffset(ZoneOffset.UTC),
            )
            val oldest = jdbcTemplate.queryForObject(
                "SELECT min(occurred_at) FROM quiz.outbox WHERE published_at IS NULL",
                OffsetDateTime::class.java,
            )
            RelayResult(sent = sent.size, failed = entries.size - sent.size, deleted = deleted, oldest?.toInstant())
        },
    )

    private fun update(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        jdbcTemplate.update(
            "UPDATE quiz.outbox SET published_at = now() WHERE id = ANY (?) AND published_at IS NULL",
            ids.toTypedArray(),
        )
    }

    private companion object {
        val ENTRY = RowMapper { rs, _ ->
            OutboxEntry(
                id = rs.getObject("id", UUID::class.java),
                tenantId = rs.getObject("tenant_id", UUID::class.java),
                eventType = rs.getString("event_type"),
                payload = rs.getString("payload"),
                traceParent = rs.getString("trace_parent"),
            )
        }
    }
}
