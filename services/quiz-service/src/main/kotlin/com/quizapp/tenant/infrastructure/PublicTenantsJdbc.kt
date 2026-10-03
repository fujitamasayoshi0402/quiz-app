package com.quizapp.tenant.infrastructure

import com.quizapp.tenant.domain.JoinableTenant
import com.quizapp.tenant.domain.PublicTenant
import com.quizapp.tenant.domain.PublicTenants
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PublicTenantsJdbc(private val jdbcTemplate: JdbcTemplate) : PublicTenants {

    override fun listFor(userId: UUID): List<PublicTenant> = jdbcTemplate.query(
        """
        SELECT t.slug, t.name, EXISTS (
            SELECT 1 FROM core.tenant_members m
            WHERE m.tenant_id = t.id AND m.user_id = ? AND m.deleted_at IS NULL
        ) AS joined
        FROM core.tenants t
        WHERE t.visibility = 'public' AND t.deleted_at IS NULL
        ORDER BY t.name, t.slug
        """,
        { rs, _ -> PublicTenant(rs.getString("slug"), rs.getString("name"), rs.getBoolean("joined")) },
        userId,
    )

    override fun findJoinable(slug: String): JoinableTenant? = jdbcTemplate.query(
        // 参加と、管理者が非公開に戻す操作が重なったときに、戻したあとで参加させない
        """
        SELECT id, slug, name FROM core.tenants
        WHERE slug = ? AND visibility = 'public' AND deleted_at IS NULL
        FOR SHARE
        """,
        { rs, _ -> JoinableTenant(rs.getObject("id", UUID::class.java), rs.getString("slug"), rs.getString("name")) },
        slug,
    ).firstOrNull()
}
