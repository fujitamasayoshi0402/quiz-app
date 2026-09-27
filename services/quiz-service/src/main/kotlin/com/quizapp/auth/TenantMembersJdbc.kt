package com.quizapp.auth

import com.quizapp.answer.domain.TenantMembers
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [TenantMembers] の実装。所属を持つ auth が `core.tenant_members` を読む。
 *
 * answer モジュールはこのクラスを知らない。インターフェース越しに使う。
 * `core.tenant_members` は行レベルセキュリティの対象外なので、テナントは [TenantContext] から取って絞る。
 */
@Component
class TenantMembersJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : TenantMembers {

    override fun filterMembers(userIds: Collection<UUID>): Set<UUID> {
        // IN 句に空のリストを渡すと SQL が壊れる
        if (userIds.isEmpty()) return emptySet()

        return jdbcTemplate.query(
            """
            SELECT user_id FROM core.tenant_members
            WHERE tenant_id = :tenantId AND user_id IN (:userIds) AND deleted_at IS NULL
            """,
            mapOf("tenantId" to TenantContext.require(), "userIds" to userIds),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }
            .toSet()
    }
}
