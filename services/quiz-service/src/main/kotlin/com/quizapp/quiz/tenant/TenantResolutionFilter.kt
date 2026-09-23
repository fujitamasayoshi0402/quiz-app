package com.quizapp.quiz.tenant

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * URL のパスからテナントを解決し、リクエストの間だけ [TenantContext] に保持する。
 *
 * `/api/t/{slug}/...` の形を前提とする。テナントを含まないパスでは何もしない。
 *
 * `core.tenants` は行レベルセキュリティの対象外のため、テナントが決まる前でも参照できる
 * （[ADR-0006](../../../../../../../docs/adr/0006-row-level-multi-tenancy.md)）。
 */
@Component
class TenantResolutionFilter(private val jdbcTemplate: JdbcTemplate) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            extractSlug(request.requestURI)?.let { slug ->
                findTenantId(slug)?.let { TenantContext.set(it) }
            }
            // Phase 1 の暫定。Phase 3 で JWT から解決するように差し替える（DEV-24）
            request.getHeader(USER_ID_HEADER)?.let { header ->
                runCatching { UUID.fromString(header) }.getOrNull()?.let { UserContext.set(it) }
            }
            filterChain.doFilter(request, response)
        } finally {
            // スレッドはプールで使い回されるため、必ず消す。
            // 残すと次のリクエストが前のテナントや利用者を引き継ぐ
            TenantContext.clear()
            UserContext.clear()
        }
    }

    private companion object {
        const val USER_ID_HEADER = "X-User-Id"
    }

    private fun extractSlug(uri: String): String? {
        val segments = uri.trim('/').split('/')
        val index = segments.indexOf("t")
        return if (index >= 0 && index + 1 < segments.size) segments[index + 1] else null
    }

    private fun findTenantId(slug: String): UUID? =
        jdbcTemplate.query(
            "SELECT id FROM core.tenants WHERE slug = ? AND deleted_at IS NULL",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            slug,
        ).firstOrNull()
}
