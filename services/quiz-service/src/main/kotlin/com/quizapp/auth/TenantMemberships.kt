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

    /** 利用者が所属するテナント。テナントの名前順に返す。 */
    fun findTenantsOf(userId: UUID): List<Membership>

    /** そのメールアドレスを持つ利用者が、テナントに所属しているか */
    fun hasMemberWithEmail(tenantId: UUID, email: String): Boolean

    /**
     * 所属させる。**すでに所属していれば何もしない。** ロールも変えない。
     * ロールを変えるのは所属の管理であり、招待の受け入れではない。
     */
    fun join(tenantId: UUID, userId: UUID, role: TenantRole)
}

/** 所属先のテナントと、そこでの役割。 */
data class Membership(val slug: String, val name: String, val role: TenantRole)

/**
 * `core.tenant_members` は行レベルセキュリティの対象外のため、
 * テナントを設定する前でも参照できる（ADR-0006）。
 * 所属判定は「どのテナントで作業するか」が決まる前に必要になる。
 */
@Component
class TenantMembershipsJdbc(private val jdbcTemplate: JdbcTemplate) : TenantMemberships {

    override fun findRole(tenantId: UUID, userId: UUID): TenantRole? = jdbcTemplate.query(
        """
            SELECT role FROM core.tenant_members
            WHERE tenant_id = ? AND user_id = ? AND deleted_at IS NULL
            """,
        { rs, _ -> TenantRole.from(rs.getString("role")) },
        tenantId,
        userId,
    ).firstOrNull()

    /**
     * **削除されたテナントも除く。** 所属だけを見ると、選んだ先で「テナントが存在しない」になる
     * （テナントの解決は [com.quizapp.tenant.TenantResolutionFilter] が削除済みを除いて行う）。
     */
    override fun findTenantsOf(userId: UUID): List<Membership> = jdbcTemplate.query(
        """
            SELECT t.slug, t.name, m.role FROM core.tenant_members m
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE m.user_id = ? AND m.deleted_at IS NULL AND t.deleted_at IS NULL
            ORDER BY t.name, t.slug
            """,
        { rs, _ -> Membership(rs.getString("slug"), rs.getString("name"), TenantRole.from(rs.getString("role"))) },
        userId,
    )

    override fun hasMemberWithEmail(tenantId: UUID, email: String): Boolean = jdbcTemplate.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM core.tenant_members m JOIN core.users u ON u.id = m.user_id
            WHERE m.tenant_id = ? AND lower(u.email) = lower(?) AND m.deleted_at IS NULL
        )
        """,
        Boolean::class.java,
        tenantId,
        email,
    ) == true

    override fun join(tenantId: UUID, userId: UUID, role: TenantRole) {
        jdbcTemplate.update(
            """
            INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES (?, ?, ?)
            ON CONFLICT (tenant_id, user_id) WHERE deleted_at IS NULL DO NOTHING
            """,
            tenantId,
            userId,
            role.name.lowercase(),
        )
    }
}
