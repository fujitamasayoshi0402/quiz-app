package com.quizapp.tenant.domain

import java.util.UUID

/**
 * テナントの公開設定（ADR-0006、ADR-0025）。
 *
 * - `PRIVATE` … 招待した人だけが所属できる
 * - `PUBLIC` … ログインした人が、公開テナントの一覧から見つけて、一般ユーザーとして自分で参加できる
 *
 * **公開にしても、クイズを読むのは所属してから。** 所属していない人にテナントの中身を見せる設定ではない
 */
enum class TenantVisibility {
    PRIVATE,
    PUBLIC,
    ;

    val value: String get() = name.lowercase()

    companion object {
        fun from(value: String): TenantVisibility = valueOf(value.uppercase())
    }
}

/** テナントの設定。いまは公開設定だけを持つ */
data class TenantSettings(val visibility: TenantVisibility)

interface TenantSettingsStore {
    fun find(tenantId: UUID): TenantSettings

    fun save(tenantId: UUID, settings: TenantSettings)
}
