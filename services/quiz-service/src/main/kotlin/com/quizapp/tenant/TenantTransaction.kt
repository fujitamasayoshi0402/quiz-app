package com.quizapp.tenant

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * テナントを設定したうえでトランザクションを実行する。
 *
 * [TenantSession] の設定はトランザクション内でしか効かず、かつ書き忘れると
 * 行レベルセキュリティによって「0 件が返るだけ」の静かな不具合になる。
 * ユースケース層がこのクラスを通して DB にアクセスすることで、設定漏れを防ぐ。
 *
 * `@Transactional` と AOP による自動適用にしなかったのは、
 * トランザクション境界の内側で確実に実行される保証が得にくいため。
 * 明示的に包むほうが、どこでテナントが効いているかがコードから読み取れる。
 */
@Component
class TenantTransaction(
    private val transactionTemplate: TransactionTemplate,
    private val tenantSession: TenantSession,
) {
    fun <T : Any> execute(block: () -> T): T =
        transactionTemplate.execute {
            tenantSession.applyCurrent()
            block()
        }

    /**
     * 結果が null になりうる処理で使う。
     *
     * 「中断中の挑戦を探す」のように、見つからないことが正常な操作がある。
     * [execute] は非 null を前提にしているため分けている。
     */
    fun <T> executeNullable(block: () -> T?): T? =
        transactionTemplate.execute {
            tenantSession.applyCurrent()
            block()
        }

    fun executeWithoutResult(block: () -> Unit) {
        transactionTemplate.executeWithoutResult {
            tenantSession.applyCurrent()
            block()
        }
    }
}
