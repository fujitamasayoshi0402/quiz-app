package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.LimitedResource
import com.quizapp.quiz.domain.TenantCapacity
import com.quizapp.quiz.domain.Usage
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * 上限は `core.tenants` の列にある（ADR-0028）。数えるのは、テナントの文脈の中の `quiz` の表。
 * 行レベルセキュリティでも絞られるが、テナントの条件も書く
 */
@Component
class TenantCapacityJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : TenantCapacity {

    override fun usage(resource: LimitedResource): Usage? {
        val tenantId = TenantContext.require()
        val (limitColumn, table) = when (resource) {
            LimitedResource.QUIZZES -> "quiz_limit" to "quiz.quizzes"
            LimitedResource.FIGURES -> "figure_limit" to "quiz.figures"
        }
        val limit = jdbcTemplate.queryForList(
            "SELECT $limitColumn FROM core.tenants WHERE id = :tenantId",
            mapOf("tenantId" to tenantId),
            Int::class.javaObjectType,
        ).firstOrNull() ?: return null
        val used = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $table WHERE tenant_id = :tenantId",
            mapOf("tenantId" to tenantId),
            Int::class.java,
        ) ?: 0
        return Usage(used, limit)
    }
}
