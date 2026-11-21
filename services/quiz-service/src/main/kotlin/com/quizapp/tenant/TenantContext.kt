package com.quizapp.tenant

import java.util.UUID

/**
 * 現在のリクエストが対象とするテナントを保持する。
 *
 * HTTP リクエストの入り口で設定し、DB アクセス時に参照する。
 * Phase 3 で Cognito の JWT からテナントを解決するようになっても、この境界は変わらない。
 */
object TenantContext {
    private val holder = ThreadLocal<UUID?>()

    fun set(tenantId: UUID) = holder.set(tenantId)

    fun get(): UUID? = holder.get()

    /** テナントが必須の処理で使う。未設定は呼び出し側のバグなので例外にする。 */
    fun require(): UUID = holder.get()
        ?: error("テナントが設定されていません。TenantContext.set を呼ばずに DB へアクセスしています")

    fun clear() = holder.remove()

    /**
     * [tenantId] を設定して [block] を流し、前の設定に戻す。要求の外（裏での読み直しなど）で、テナントを決めて DB を読むときに使う
     */
    fun <T> within(tenantId: UUID, block: () -> T): T {
        val previous = holder.get()
        holder.set(tenantId)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}
