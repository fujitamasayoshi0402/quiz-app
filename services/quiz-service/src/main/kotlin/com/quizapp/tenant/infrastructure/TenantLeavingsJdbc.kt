package com.quizapp.tenant.infrastructure

import com.quizapp.tenant.domain.TenantLeaveBlock
import com.quizapp.tenant.domain.TenantLeavings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TenantLeavingsJdbc(private val jdbcTemplate: JdbcTemplate) : TenantLeavings {

    override fun blockFor(tenantId: UUID, userId: UUID): TenantLeaveBlock? {
        jdbcTemplate.query("SELECT id FROM core.tenants WHERE id = ? FOR UPDATE", { _, _ -> }, tenantId)
        return jdbcTemplate.query(
            """
            SELECT u.shared, m.role = 'admin' AND NOT EXISTS (
                SELECT 1 FROM core.tenant_members o
                WHERE o.tenant_id = m.tenant_id AND o.user_id <> m.user_id AND o.role = 'admin' AND o.deleted_at IS NULL
            ) AS last_admin
            FROM core.tenant_members m JOIN core.users u ON u.id = m.user_id
            WHERE m.tenant_id = ? AND m.user_id = ? AND m.deleted_at IS NULL
            """,
            { rs, _ ->
                when {
                    rs.getBoolean("shared") -> TenantLeaveBlock.SHARED_ACCOUNT
                    rs.getBoolean("last_admin") -> TenantLeaveBlock.LAST_ADMIN
                    else -> null
                }
            },
            tenantId,
            userId,
        ).firstOrNull()
    }

    override fun leave(tenantId: UUID, userId: UUID) {
        jdbcTemplate.update(
            """
            UPDATE core.tenant_members SET deleted_at = now()
            WHERE tenant_id = ? AND user_id = ? AND deleted_at IS NULL
            """,
            tenantId,
            userId,
        )
    }
}
