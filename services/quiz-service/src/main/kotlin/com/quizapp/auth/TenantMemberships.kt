package com.quizapp.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * テナントへの所属とロールを引く。
 *
 * 認証方式が変わってもここは変わらない。**ロールは Cognito ではなくアプリケーションのデータ**とし、
 * `core.tenant_members` を唯一の出どころにする。
 * 認証基盤側にロールを持たせると、招待・脱退のたびに外部サービスと同期が要る。
 */
interface TenantMemberships {

    /** 所属していなければ null。 */
    fun findRole(tenantId: UUID, userId: UUID): TenantRole?
}

/**
 * `core.tenant_members` は行レベルセキュリティの対象外のため、
 * テナントを設定する前でも参照できる（ADR-0006）。
 * 所属判定は「どのテナントで作業するか」が決まる前に必要になる。
 */
@Component
class TenantMembershipsJdbc(private val jdbcTemplate: JdbcTemplate) : TenantMemberships {

    override fun findRole(tenantId: UUID, userId: UUID): TenantRole? =
        jdbcTemplate.query(
            """
            SELECT role FROM core.tenant_members
            WHERE tenant_id = ? AND user_id = ? AND deleted_at IS NULL
            """,
            { rs, _ -> TenantRole.from(rs.getString("role")) },
            tenantId,
            userId,
        ).firstOrNull()
}
