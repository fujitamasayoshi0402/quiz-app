package com.quizapp.support

import com.quizapp.quiz.support.TestPostgres
import java.util.UUID

/**
 * テストで使う利用者と所属。
 *
 * テナント配下のエンドポイントは**所属していないと触れない**ため、
 * どのテストでも利用者を用意する必要がある。
 *
 * 利用者そのものはテスト間で共有し、消さない。**所属はテナントごとに作って片付ける。**
 * ロールはテナント単位に決まるので、共有すると片付け漏れが別のテストに漏れる。
 */
object TestAuth {

    /** 既定の操作者。すべての管理 API をこの利用者で叩く */
    val ADMIN: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    /** 管理者ではない所属者。ロールの検証に使う */
    val MEMBER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    /** どのテナントにも所属していない利用者 */
    val OUTSIDER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c3")

    private val shared = listOf(ADMIN to "共有管理者", MEMBER to "共有一般ユーザー", OUTSIDER to "部外者")

    /** 共有の利用者を用意する。何度呼んでも増えない。 */
    fun ensureUsers() {
        shared.forEach { (id, name) ->
            TestPostgres.adminJdbcTemplate.update(
                """
                INSERT INTO core.users (id, external_id, display_name) VALUES (?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                id,
                "test-$id",
                name,
            )
        }
    }

    /** 指定したテナントに [ADMIN] を管理者として所属させる。 */
    fun joinAsAdmin(vararg tenantIds: UUID) = tenantIds.forEach { join(it, ADMIN, "admin") }

    fun join(tenantId: UUID, userId: UUID, role: String) {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES (?, ?, ?)",
            tenantId,
            userId,
            role,
        )
    }

    /** テナントを消す前に呼ぶ。所属が残っていると外部キーで消せない。 */
    fun leaveAll(vararg tenantIds: UUID) {
        tenantIds.forEach {
            TestPostgres.adminJdbcTemplate.update("DELETE FROM core.tenant_members WHERE tenant_id = ?", it)
        }
    }
}
