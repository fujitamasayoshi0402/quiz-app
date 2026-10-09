package com.quizapp.invitation.infrastructure

import com.quizapp.auth.TenantRole
import com.quizapp.invitation.domain.Invitation
import com.quizapp.invitation.domain.InvitationRepository
import com.quizapp.invitation.domain.ReceivedInvitation
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JDBC ではなく SQL で書く。取得はテナントとの結合やロックを伴い、更新は条件付きの 1 文で済むため。
 */
@Component
class InvitationRepositoryJdbc(private val jdbcTemplate: JdbcTemplate) : InvitationRepository {

    override fun findOpen(tenantId: UUID): List<Invitation> = jdbcTemplate.query(
        """
        SELECT * FROM core.invitations
        WHERE tenant_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
        ORDER BY created_at DESC, id
        """,
        invitationMapper,
        tenantId,
    )

    override fun create(
        tenantId: UUID,
        email: String,
        role: TenantRole,
        tokenHash: String,
        invitedBy: UUID,
        expiresAt: Instant,
    ): Invitation = jdbcTemplate.query(
        """
        INSERT INTO core.invitations (tenant_id, email, role, token_hash, invited_by, expires_at)
        VALUES (?, ?, ?, ?, ?, ?)
        RETURNING *
        """,
        invitationMapper,
        tenantId,
        email,
        role.name.lowercase(),
        tokenHash,
        invitedBy,
        Timestamp.from(expiresAt),
    ).single()

    override fun revokeOpenTo(tenantId: UUID, email: String) {
        jdbcTemplate.update(
            """
            UPDATE core.invitations SET revoked_at = now()
            WHERE tenant_id = ? AND lower(email) = lower(?) AND accepted_at IS NULL AND revoked_at IS NULL
            """,
            tenantId,
            email,
        )
    }

    override fun revoke(tenantId: UUID, id: UUID): Boolean = jdbcTemplate.update(
        """
        UPDATE core.invitations SET revoked_at = now()
        WHERE tenant_id = ? AND id = ? AND accepted_at IS NULL AND revoked_at IS NULL
        """,
        tenantId,
        id,
    ) > 0

    override fun findByTokenHash(tokenHash: String): ReceivedInvitation? = findReceived(tokenHash, lock = false)

    override fun lockByTokenHash(tokenHash: String): ReceivedInvitation? = findReceived(tokenHash, lock = true)

    override fun markAccepted(id: UUID, userId: UUID) {
        jdbcTemplate.update(
            "UPDATE core.invitations SET accepted_at = now(), accepted_by = ? WHERE id = ?",
            userId,
            id,
        )
    }

    /** `FOR UPDATE OF i` で招待の行だけをロックする。テナントの行まで止めると、同じテナントの招待の受け入れが並ばなくなる */
    private fun findReceived(tokenHash: String, lock: Boolean): ReceivedInvitation? = jdbcTemplate.query(
        """
        SELECT i.*, t.slug AS tenant_slug, t.name AS tenant_name
        FROM core.invitations i JOIN core.tenants t ON t.id = i.tenant_id
        WHERE i.token_hash = ? AND t.deleted_at IS NULL
        ${if (lock) "FOR UPDATE OF i" else ""}
        """,
        { rs, row ->
            ReceivedInvitation(
                invitation = invitationMapper.mapRow(rs, row),
                tenantSlug = rs.getString("tenant_slug"),
                tenantName = rs.getString("tenant_name"),
            )
        },
        tokenHash,
    ).firstOrNull()

    private val invitationMapper = RowMapper { rs, _ ->
        Invitation(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            email = rs.getString("email"),
            role = TenantRole.from(rs.getString("role")),
            expiresAt = rs.instant("expires_at"),
            createdAt = rs.instant("created_at"),
            acceptedAt = rs.getTimestamp("accepted_at")?.toInstant(),
            acceptedBy = rs.getObject("accepted_by", UUID::class.java),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        )
    }

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
}
