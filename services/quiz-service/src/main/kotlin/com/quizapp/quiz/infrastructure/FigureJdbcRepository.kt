package com.quizapp.quiz.infrastructure

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

    override fun add(id: UUID) {
        jdbcTemplate.update(
            "INSERT INTO quiz.figures (id, tenant_id) VALUES (:id, :tenantId)",
            mapOf("id" to id, "tenantId" to TenantContext.require()),
        )
    }

    override fun exists(id: UUID): Boolean = jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM quiz.figures WHERE id = :id)",
        mapOf("id" to id),
        Boolean::class.java,
    ) == true

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM quiz.figures WHERE id = :id", mapOf("id" to id)) > 0
}
