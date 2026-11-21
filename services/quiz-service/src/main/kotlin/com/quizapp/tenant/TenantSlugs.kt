package com.quizapp.tenant

import com.quizapp.cache.ReadCaches
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * slug からテナントの ID を引く。テナント配下のどの要求でも引くため、**メモリに持つ**（ADR-0038）。
 *
 * slug は付け替えず、テナントは消さないため、一度決まった対応は変わらない。読み直さない。
 * 見つからなかったことも持つため、テナントを作ったら [forget] で消す。
 *
 * `core.tenants` は行レベルセキュリティの対象外のため、テナントが決まる前でも参照できる
 * （[ADR-0006](../../../../../../../docs/adr/0006-row-level-multi-tenancy.md)）。
 */
@Component
class TenantSlugs(jdbcTemplate: JdbcTemplate, caches: ReadCaches) {

    private val ids = caches.stable<String, UUID> { slug ->
        jdbcTemplate.query(
            "SELECT id FROM core.tenants WHERE slug = ? AND deleted_at IS NULL",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            slug,
        ).firstOrNull()
    }

    fun idOf(slug: String): UUID? = ids.get(slug)

    /** テナントを作ったあとに呼ぶ */
    fun forget(slug: String) = ids.forget(slug)
}
