package com.quizapp.support

import com.quizapp.quiz.support.TestPostgres
import java.util.UUID

/**
 * テストで使うテナント。**テストごとに新しく作り、片付けない。**
 *
 * テナントの分離（行レベルセキュリティ）が、そのままテスト同士の分離になる。
 * 別のテストが残した行は、別のテナントのものとして見えない。
 * 片付けを書かないので、テーブルを足しても各テストの後始末を直さずに済む。
 * 後始末を書き忘れても静かに通り、次のテストだけが壊れる、という事故も起きない。
 *
 * ID と slug は毎回作る。固定すると、テスト同士で値がぶつかったときに一意制約で落ちる。
 */
data class TestTenant(val id: UUID, val slug: String) {

    /** 利用者を所属させる。 */
    fun join(userId: UUID, role: String = "member"): TestTenant = apply { TestAuth.join(id, userId, role) }

    /** [TestAuth.ADMIN] を管理者として所属させる。管理 API でデータを用意するテストが使う */
    fun withAdmin(): TestTenant = join(TestAuth.ADMIN, "admin")

    companion object {
        fun create(name: String = "テストテナント"): TestTenant {
            TestAuth.ensureUsers()
            val id = UUID.randomUUID()
            // slug は 3〜32 文字の英小文字・数字・ハイフン（core.tenants の制約）
            val slug = "t-" + id.toString().replace("-", "").take(SLUG_RANDOM_LENGTH)
            TestPostgres.adminJdbcTemplate.update(
                "INSERT INTO core.tenants (id, slug, name) VALUES (?, ?, ?)",
                id,
                slug,
                name,
            )
            return TestTenant(id, slug)
        }

        private const val SLUG_RANDOM_LENGTH = 16
    }
}
