package com.quizapp.tenant

import java.util.UUID

/**
 * 現在のリクエストの利用者。
 *
 * Phase 1 ではリクエストヘッダから受け取る暫定実装。
 * Phase 3 で Cognito の JWT から解決するように差し替えるが、**参照側のコードは変わらない**。
 * 差し替え範囲をここに閉じ込めるために、利用側は常にこのオブジェクトを通す。
 */
object UserContext {
    private val holder = ThreadLocal<UUID?>()

    fun set(userId: UUID) = holder.set(userId)

    fun get(): UUID? = holder.get()

    fun require(): UUID = holder.get() ?: throw UserNotIdentifiedException()

    fun clear() = holder.remove()
}

/**
 * 利用者を特定できない。
 *
 * テナント解決の失敗（[IllegalStateException]）と区別する。
 * まとめて扱うと「テナントが存在しません」と返ってしまい、原因が追えない。
 */
class UserNotIdentifiedException : RuntimeException("利用者が特定できません")
