package com.quizapp.support

import com.quizapp.quiz.support.TestPostgres
import java.util.UUID

/**
 * テストで使う利用者と所属。
 *
 * テナント配下のエンドポイントは**所属していないと触れない**ため、
 * どのテストでも利用者を用意する必要がある。
 *
 * 共有の利用者はテスト間で使い回す。所属はテナント（[TestTenant]）ごとに作るので、
 * ロールがテストをまたいで漏れることはない。
 * 利用者単位の状態（中断中の挑戦など）もテナントごとに持つため、共有して困らない。
 * 利用者そのものを区別したいテストは [createUser] で作る。
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

    /**
     * テスト専用の利用者を作る。共有の利用者と区別したいときに使う。片付けない。
     * [email] は認証基盤で確認済みのメールアドレスとして持つ（招待の照合に使う）
     */
    fun createUser(name: String = "テスト利用者", email: String? = null): UUID {
        val id = UUID.randomUUID()
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.users (id, external_id, email, display_name) VALUES (?, ?, ?, ?)",
            id,
            "test-$id",
            email,
            name,
        )
        return id
    }

    /**
     * 利用者として API を呼ぶときの `Authorization` の値。
     * 利用者は `test-<ID>` を認証基盤の ID として持つ（[ensureUsers]、[createUser]）
     */
    fun bearer(userId: UUID): String = "Bearer ${TestJwt.issue("test-$userId")}"

    fun join(tenantId: UUID, userId: UUID, role: String) {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES (?, ?, ?)",
            tenantId,
            userId,
            role,
        )
    }
}
