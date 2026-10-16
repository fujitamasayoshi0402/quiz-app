package com.quizapp.quiz.infrastructure.newsquiz

import com.quizapp.tenant.TenantTransaction
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** 取り込む先のテナントと、そこにたまっている AI の下書きの数（ADR-0031） */
class NewsQuizDrafts(private val jdbcTemplate: JdbcTemplate, private val tenantTransaction: TenantTransaction) {

    // core.tenants は行レベルセキュリティの外にある（V5）
    fun tenantId(slug: String): UUID? = jdbcTemplate.queryForList(
        "SELECT id FROM core.tenants WHERE slug = ? AND deleted_at IS NULL",
        UUID::class.java,
        slug,
    ).firstOrNull()

    /** 確かめていない（下書きのままの）AI の下書き。テナントの文脈（`TenantContext`）の中で呼ぶ */
    fun pendingCount(): Int = tenantTransaction.execute {
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM quiz.quizzes
            WHERE source_url IS NOT NULL AND status = 'draft' AND deleted_at IS NULL
            """.trimIndent(),
            Int::class.java,
        ) ?: 0
    }
}
