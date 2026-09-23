package com.quizapp.auth

import java.util.UUID

/** テナント内での役割。`core.tenant_members.role` と対応する。 */
enum class TenantRole {
    /** カテゴリ・難易度・クイズを管理できる */
    ADMIN,

    /** クイズに回答できる */
    MEMBER,
    ;

    companion object {
        fun from(value: String): TenantRole =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalStateException("不明なロールです: $value")
    }
}

/**
 * 認証された利用者と、**いま対象にしているテナントでの役割**。
 *
 * ロールはテナントごとに決まる。同じ人がテナント A では管理者、B では一般ユーザーになりうるため、
 * 利用者そのものではなくリクエスト単位で持つ。
 */
data class CurrentUser(
    val id: UUID,
    val role: TenantRole? = null,
)

/**
 * 現在のリクエストの利用者。
 *
 * 認証方式が変わっても参照側のコードが変わらないよう、利用側は常にこのオブジェクトを通す。
 * 設定するのは [AuthenticationFilter] と [TenantAccessInterceptor] だけ。
 */
object UserContext {
    private val holder = ThreadLocal<CurrentUser?>()

    fun set(user: CurrentUser) = holder.set(user)

    fun get(): CurrentUser? = holder.get()

    fun require(): UUID = holder.get()?.id ?: throw UserNotIdentifiedException()

    fun clear() = holder.remove()
}

/**
 * 利用者を特定できない。
 *
 * テナント解決の失敗（[IllegalStateException]）と区別する。
 * まとめて扱うと「テナントが存在しません」と返ってしまい、原因が追えない。
 */
class UserNotIdentifiedException : RuntimeException("利用者が特定できません")

/**
 * 認証はできたが、そのテナントに所属していない。
 *
 * **所属していないテナントは「存在しない」として扱う。** 403 と 404 を区別すると、
 * テナントが実在することが分かってしまう。
 */
class TenantAccessDeniedException : RuntimeException("このテナントにはアクセスできません")

/** 所属はしているが、管理者ではない。テナントの存在は既知なので 403 で返す。 */
class AdminRoleRequiredException : RuntimeException("この操作には管理者の権限が必要です")
