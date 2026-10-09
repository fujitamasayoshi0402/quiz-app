package com.quizapp.tenant

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * PostgreSQL のセッション変数 `app.tenant_id` を設定する。
 * 行レベルセキュリティのポリシーがこの値を参照してテナントを絞り込む。
 *
 * `set_config` の第 3 引数に true を渡すことで `SET LOCAL` 相当になり、
 * **トランザクションの終了時に PostgreSQL が自動で元に戻す**。
 * 接続をプールへ返すときのリセット処理が不要になり、リセット漏れが構造的に起きない。
 *
 * その代わり、トランザクションの外で呼んでも効果がない。
 * 読み取りだけの処理でもトランザクションが必要になる。
 */
@Component
class TenantSession(private val jdbcTemplate: JdbcTemplate) {

    fun apply(tenantId: UUID) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "トランザクションの外では app.tenant_id を設定できません。" +
                "SET LOCAL 相当の設定はトランザクション終了時に破棄されるため、" +
                "呼び出し側を @Transactional にしてください"
        }
        // 文字列連結ではなくプレースホルダで渡す。set_config は関数なので値をバインドできる
        jdbcTemplate.queryForObject(
            "SELECT set_config('app.tenant_id', ?, true)",
            String::class.java,
            tenantId.toString(),
        )
    }

    fun applyCurrent() = apply(TenantContext.require())

    /** 現在の接続に設定されている値。検証とデバッグに使う。 */
    fun current(): String? = jdbcTemplate.queryForObject(
        "SELECT nullif(current_setting('app.tenant_id', true), '')",
        String::class.java,
    )
}
