package com.quizapp.support.fake

import com.quizapp.tenant.TenantSession
import com.quizapp.tenant.TenantTransaction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * DB を使わない [TenantTransaction]。ユースケースの単体テストで使う。
 *
 * 外すのはトランザクションと、DB のセッション変数の設定だけ。
 * **テナントが決まっていることの確認は残す。** 本物と同じく、`TenantContext` が空なら失敗する。
 *
 * 本物のクラスをそのまま使うのは、ユースケースが通る入口を単体テストでも変えないため。
 * 差し替えるのはその内側の 2 つに限る。
 */
fun fakeTenantTransaction(): TenantTransaction =
    TenantTransaction(TransactionTemplate(NoOpTransactionManager()), NoDbTenantSession())

private class NoOpTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()

    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

    override fun doCommit(status: DefaultTransactionStatus) = Unit

    override fun doRollback(status: DefaultTransactionStatus) = Unit
}

/** `applyCurrent` は本物のまま動き、テナントが無ければ失敗する。DB には触れない */
private class NoDbTenantSession : TenantSession(JdbcTemplate()) {
    override fun apply(tenantId: UUID) = Unit
}
