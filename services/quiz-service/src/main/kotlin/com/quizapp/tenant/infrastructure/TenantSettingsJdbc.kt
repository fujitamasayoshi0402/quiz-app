package com.quizapp.tenant.infrastructure

import com.quizapp.tenant.domain.TenantSettings
import com.quizapp.tenant.domain.TenantSettingsStore
import com.quizapp.tenant.domain.TenantVisibility
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `core.tenants` の設定の列を読み書きする。
 *
 * `core.tenants` は行レベルセキュリティの対象外（ADR-0006）。**どの行に触れるかは、パスから解決したテナントの ID だけで決める。**
 * 要求の本文やほかの値から、テナントを選ばない
 */
@Component
class TenantSettingsJdbc(private val jdbcTemplate: JdbcTemplate) : TenantSettingsStore {

    override fun find(tenantId: UUID): TenantSettings = jdbcTemplate.query(
        // 作った人がいるテナントは、利用者が作ったもの。公開にできない（ADR-0028）
        "SELECT visibility, created_by IS NULL AS can_be_public FROM core.tenants WHERE id = ? AND deleted_at IS NULL",
        { rs, _ -> TenantSettings(TenantVisibility.from(rs.getString("visibility")), rs.getBoolean("can_be_public")) },
        tenantId,
    ).single()

    override fun save(tenantId: UUID, settings: TenantSettings) {
        jdbcTemplate.update(
            "UPDATE core.tenants SET visibility = ? WHERE id = ? AND deleted_at IS NULL",
            settings.visibility.value,
            tenantId,
        )
    }
}
