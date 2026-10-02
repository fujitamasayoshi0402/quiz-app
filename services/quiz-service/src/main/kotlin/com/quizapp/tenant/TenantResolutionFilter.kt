package com.quizapp.tenant

import com.quizapp.logging.LogContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
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
@Order(TenantResolutionFilter.ORDER)
class TenantResolutionFilter(private val jdbcTemplate: JdbcTemplate) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            // 生の URI ではなく、Spring がルートを選ぶときと同じく、デコードしてパスの引数（`;` 以降）を外したパスから取る（DEV-114）。
            // 解釈が食い違うと、テナントを決めたパスと、振り分けたルートが別のものを指しうる
            extractSlug(UrlPathHelper.defaultInstance.getPathWithinApplication(request))?.let { slug ->
                findTenantId(slug)?.let {
                    TenantContext.set(it)
                    LogContext.putTenant(it)
                }
            }
            filterChain.doFilter(request, response)
        } finally {
            // スレッドはプールで使い回されるため、必ず消す。
            // 残すと次のリクエストが前のテナントを引き継ぐ。
            // ログの文脈（MDC）は残す。要求の終わりの 1 行に載せ、RequestLogFilter が消す
            TenantContext.clear()
        }
    }

    companion object {
        /** 利用者の所属判定にテナントが要るため、認証より先に動く */
        const val ORDER = 10
    }

    private fun extractSlug(uri: String): String? {
        val segments = uri.trim('/').split('/')
        val index = segments.indexOf("t")
        return if (index >= 0 && index + 1 < segments.size) segments[index + 1] else null
    }

    private fun findTenantId(slug: String): UUID? = jdbcTemplate.query(
        "SELECT id FROM core.tenants WHERE slug = ? AND deleted_at IS NULL",
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        slug,
    ).firstOrNull()
}
