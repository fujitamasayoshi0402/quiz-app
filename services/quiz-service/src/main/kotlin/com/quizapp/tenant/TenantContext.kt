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
}
