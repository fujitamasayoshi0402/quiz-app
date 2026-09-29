package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `quiz.figures` を読み書きする。ID をアプリが作るため、Spring Data JDBC の save（ID があれば UPDATE する）は使わない。
 *
 * 読むときにテナントで絞っていないのは、行レベルセキュリティが DB 側で効くため。
 */
@Component
class FigureJdbcRepository(private val jdbcTemplate: NamedParameterJdbcTemplate) : FigureRepository {

    override fun add(id: UUID, kind: FigureKind) {
        jdbcTemplate.update(
            "INSERT INTO quiz.figures (id, tenant_id, kind) VALUES (:id, :tenantId, :kind)",
            mapOf("id" to id, "tenantId" to TenantContext.require(), "kind" to kind.name.lowercase()),
        )
    }

    override fun findKind(id: UUID): FigureKind? = jdbcTemplate.queryForList(
        "SELECT kind FROM quiz.figures WHERE id = :id",
        mapOf("id" to id),
        String::class.java,
    ).firstOrNull()?.let { FigureKind.valueOf(it.uppercase()) }

    override fun findExisting(ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return jdbcTemplate.queryForList(
            "SELECT id FROM quiz.figures WHERE id IN (:ids)",
            mapOf("ids" to ids),
            UUID::class.java,
        ).filterNotNull().toSet()
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM quiz.figures WHERE id = :id", mapOf("id" to id)) > 0
}
