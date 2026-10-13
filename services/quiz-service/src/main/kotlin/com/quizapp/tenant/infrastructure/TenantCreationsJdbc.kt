package com.quizapp.tenant.infrastructure

import com.quizapp.tenant.domain.TenantCreationBlock
import com.quizapp.tenant.domain.TenantCreationNotAllowedException
import com.quizapp.tenant.domain.TenantCreations
import com.quizapp.tenant.domain.TenantLimits
import com.quizapp.tenant.domain.TenantSlugTakenException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TenantCreationsJdbc(private val jdbcTemplate: JdbcTemplate) : TenantCreations {

    override fun blockFor(userId: UUID): TenantCreationBlock? = jdbcTemplate.query(
        """
        SELECT u.shared, EXISTS (
            SELECT 1 FROM core.tenants t WHERE t.created_by = u.id AND t.deleted_at IS NULL
        ) AS created
        FROM core.users u WHERE u.id = ?
        """,
        { rs, _ ->
            when {
                rs.getBoolean("shared") -> TenantCreationBlock.SHARED_ACCOUNT
                rs.getBoolean("created") -> TenantCreationBlock.ALREADY_CREATED
                else -> null
            }
        },
        userId,
    ).firstOrNull()

    override fun create(slug: String, name: String, createdBy: UUID, limits: TenantLimits): UUID = try {
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO core.tenants (slug, name, created_by, quiz_limit, figure_limit)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID::class.java,
                slug,
                name,
                createdBy,
                limits.quizzes,
                limits.figures,
            ),
        )
    } catch (e: DuplicateKeyException) {
        // どちらの一意制約に当たったかで分ける。制約の名前は V2（slug）と V19（作った人）
        when {
            e.message.orEmpty().contains(CREATED_BY_KEY) ->
                throw TenantCreationNotAllowedException(TenantCreationBlock.ALREADY_CREATED)

            e.message.orEmpty().contains(SLUG_KEY) -> throw TenantSlugTakenException(slug)

            else -> throw e
        }
    }

    private companion object {
        const val SLUG_KEY = "tenants_slug_key"
        const val CREATED_BY_KEY = "tenants_created_by_key"
    }
}
