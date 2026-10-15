package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.BookmarkRepository
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** [BookmarkRepository] の実装。answer スキーマだけを読む。 */
@Component
class BookmarkJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : BookmarkRepository {

    override fun findQuizIds(userId: UUID): Set<UUID> = jdbcTemplate.query(
        "SELECT quiz_id FROM answer.bookmarks WHERE user_id = :userId",
        mapOf("userId" to userId),
    ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) }
        .toSet()

    override fun add(userId: UUID, quizId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO answer.bookmarks (tenant_id, user_id, quiz_id) VALUES (:tenantId, :userId, :quizId)
            ON CONFLICT DO NOTHING
            """,
            mapOf("tenantId" to TenantContext.require(), "userId" to userId, "quizId" to quizId),
        )
    }

    override fun remove(userId: UUID, quizId: UUID) {
        jdbcTemplate.update(
            "DELETE FROM answer.bookmarks WHERE user_id = :userId AND quiz_id = :quizId",
            mapOf("userId" to userId, "quizId" to quizId),
        )
    }
}
