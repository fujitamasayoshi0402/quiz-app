package com.quizapp.tenant

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * テナント境界のセキュリティテスト。**テナント配下の全エンドポイント**を対象にする。
 *
 * 攻撃者は「自分のテナント（own）の URL から、別テナント（victim）のリソース ID を指定する」利用者。
 * **攻撃者は両方のテナントに管理者として所属している。** 所属の判定では止まらない組み合わせにして、
 * テナント境界そのもの（RLS とリポジトリ層）だけが守っている状態で確かめる。
 *
 * 各リクエストについて次を検証する。
 * - 期待したステータスと理由で拒否される。**理由まで見る**のは、URL の組み立て違いや
 *   入力の不備で 404 / 400 になっても、テストが素通りしてしまうため
 * - 応答に victim の ID・文字列が含まれない
 * - victim のデータが 1 行も変化していない（404 を返しつつ裏で書き換える実装を検出する）
 *
 * **検証漏れは仕組みで防ぐ。** アプリケーションの全エンドポイントを Spring から列挙し、
 * ここに検証ケースがないものがあればテストが落ちる。エンドポイントを足したら、ケースも足す。
 *
 * テナントの外に置くエンドポイントは [OUTSIDE_TENANT] に理由とともに載せる。
 * 載せたものはここでは検証しないため、**境界の検証を別のテストで用意する。**
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantBoundaryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        /** victim の文字列にはすべてこれを含める。応答に現れたら漏洩 */
        private const val SECRET = "VICTIM-SECRET"

        private const val CATEGORY_NOT_FOUND = "指定されたカテゴリは存在しません"
        private const val DIFFICULTY_NOT_FOUND = "指定された難易度は存在しません"
        private const val QUIZ_NOT_FOUND = "指定されたクイズは存在しません"
        private const val ATTEMPT_NOT_FOUND = "指定された挑戦は存在しません"
        private const val DELETED_NOT_FOUND = "削除済みの項目が見つかりません"
        private const val IMPORT_REJECTED = "取り込めない行があります。1 件も取り込んでいません"

        private val TENANT_SCOPED = Regex("/api/t/\\{slug}/(admin|play)/.+")

        /**
         * テナントの外に置くエンドポイントと、その理由。
         *
         * **例外を増やすほど境界の外が広がる。** 足すときは理由と、境界を検証しているテストを書く。
         */
        private val OUTSIDE_TENANT = mapOf(
            "GET /api/me/tenants" to
                "テナントを選ぶ前に呼ぶ。参照するのは RLS の対象外の core だけで、範囲は利用者本人に絞る（MyTenantsApiTest）",
        )
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    private lateinit var ownTenant: TestTenant
    private lateinit var victimTenant: TestTenant

    // --- 網羅性 ---------------------------------------------------------------

    @Test
    @DisplayName("テナント配下の全エンドポイントに、境界の検証ケースがある")
    fun everyEndpointIsProbed() {
        val endpoints = endpoints() - OUTSIDE_TENANT.keys
        val probed = probes(Ids.placeholder()).map { it.endpoint }.toSet()

        assertThat(endpoints - probed)
            .describedAs("境界の検証ケースがないエンドポイント。probes() にケースを足す")
            .isEmpty()
        assertThat(probed - endpoints)
            .describedAs("存在しないエンドポイントの検証ケース。URL の変更に追従していない")
            .isEmpty()
    }

    @Test
    @DisplayName("許可したものを除き、全エンドポイントがテナント配下の admin / play にある")
    fun everyEndpointIsUnderTenant() {
        // テナントを含まない API があると、RLS のセッション変数が設定されないまま DB に触れる。
        // admin / play 以外のパスは、ロールの判定（TenantAccessInterceptor）の外に出る
        val outside = endpoints().filterNot { it.substringAfter(' ').matches(TENANT_SCOPED) }

        assertThat(outside.toSet())
            .describedAs("テナントの外にあるエンドポイント。意図したものなら OUTSIDE_TENANT に理由とともに載せる")
            .isEqualTo(OUTSIDE_TENANT.keys)
    }

    // --- 境界 -----------------------------------------------------------------

    @TestFactory
    @DisplayName("別テナントのリソースを指定した操作は、結果を返さず何も変えない")
    fun crossTenantAccessIsRejected(): List<DynamicTest> {
        val ids = prepare()
        val victimIds = victimRowIds()
        return probes(ids).map { probe ->
            DynamicTest.dynamicTest("${probe.endpoint} ${probe.label}") { verify(probe, victimIds) }
        }
    }

    private fun verify(probe: Probe, victimIds: Set<UUID>) {
        val before = victimSnapshot()
        val result = perform(probe)
        val body = result.response.contentAsString
        val headers = result.response.headerNames.joinToString { result.response.getHeaders(it).toString() }
        val text = body + headers

        // 失敗はまとめて報告する。ステータスが違うだけなのか、漏洩や書き換えまで起きているのかで重さが違う
        SoftAssertions.assertSoftly { softly ->
            softly.assertThat(result.response.status).describedAs("ステータス: $body").isEqualTo(probe.status)
            if (probe.detail != null) {
                softly.assertThat(body).describedAs("拒否の理由").contains("\"detail\":\"${probe.detail}\"")
            }

            softly.assertThat(text).describedAs("victim の文字列が応答に含まれる").doesNotContain(SECRET)
            // リクエストに含めた ID は、ProblemDetail の instance などで応答に現れうる
            val requested = probe.vars.values + probe.query.values + probe.bodyIds
            softly.assertThat(text)
                .describedAs("victim の ID が応答に含まれる")
                .doesNotContain(*(victimIds - requested.toSet()).map(UUID::toString).toTypedArray())

            softly.assertThat(victimSnapshot()).describedAs("victim のデータが変化した").isEqualTo(before)
        }
    }

    private fun perform(probe: Probe): MvcResult {
        val url = probe.vars.entries.fold(probe.path.replace("{slug}", ownTenant.slug)) { acc, (name, id) ->
            acc.replace("{$name}", id.toString())
        }
        assertThat(url).describedAs("パス変数が埋まっていない").doesNotContain("{")

        val request = MockMvcRequestBuilders.request(probe.method, url)
            .header("X-User-Id", TestAuth.ADMIN.toString())
        probe.query.forEach { (name, id) -> request.queryParam(name, id.toString()) }
        probe.body?.let { request.contentType(MediaType.APPLICATION_JSON).content(it) }
        return mockMvc.perform(request).andReturn()
    }

    // --- 検証ケース -----------------------------------------------------------

    private fun probes(ids: Ids): List<Probe> =
        categoryProbes(ids) + difficultyProbes(ids) + quizProbes(ids) + quizImportProbes() + trashProbes(ids) +
            playCategoryProbes() + attemptProbes(ids)

    private fun categoryProbes(ids: Ids): List<Probe> {
        val base = "/api/t/{slug}/admin/categories"
        val victim = mapOf("id" to ids.victimCategory)
        return listOf(
            Probe(HttpMethod.GET, base, "一覧に出ない", status = 200),
            Probe(HttpMethod.POST, base, "作成は自テナントに入る", body = """{"name":"境界テスト"}""", status = 201),
            Probe(HttpMethod.GET, "$base/{id}", "", victim, status = 404, detail = CATEGORY_NOT_FOUND),
            Probe(
                HttpMethod.PUT,
                "$base/{id}",
                "",
                victim,
                body = """{"name":"書き換え"}""",
                status = 404,
                detail = CATEGORY_NOT_FOUND,
            ),
            Probe(HttpMethod.GET, "$base/{id}/deletion-impact", "", victim, status = 404, detail = CATEGORY_NOT_FOUND),
            Probe(HttpMethod.DELETE, "$base/{id}", "", victim, status = 404, detail = CATEGORY_NOT_FOUND),
        )
    }

    private fun difficultyProbes(ids: Ids): List<Probe> {
        val base = "/api/t/{slug}/admin/categories/{categoryId}/difficulties"
        val body = """{"name":"書き換え","level":1}"""
        // victim のカテゴリ配下として指定する場合と、自分のカテゴリ配下として victim の難易度を指定する場合
        val victim = mapOf("categoryId" to ids.victimCategory, "id" to ids.victimDifficulty)
        val mixed = mapOf("categoryId" to ids.ownCategory, "id" to ids.victimDifficulty)
        val victimParent = mapOf("categoryId" to ids.victimCategory)
        return listOf(
            Probe(HttpMethod.GET, base, "", victimParent, status = 404, detail = CATEGORY_NOT_FOUND),
            Probe(HttpMethod.POST, base, "", victimParent, body = body, status = 404, detail = CATEGORY_NOT_FOUND),
        ) + listOf(
            "victim のカテゴリ配下" to victim,
            "自分のカテゴリ配下" to mixed,
        ).flatMap { (label, vars) ->
            val detail = if (vars == victim) CATEGORY_NOT_FOUND else DIFFICULTY_NOT_FOUND
            listOf(
                Probe(HttpMethod.GET, "$base/{id}", label, vars, status = 404, detail = detail),
                Probe(HttpMethod.PUT, "$base/{id}", label, vars, body = body, status = 404, detail = detail),
                Probe(HttpMethod.GET, "$base/{id}/deletion-impact", label, vars, status = 404, detail = detail),
                Probe(HttpMethod.DELETE, "$base/{id}", label, vars, status = 404, detail = detail),
            )
        }
    }

    private fun quizProbes(ids: Ids): List<Probe> {
        val base = "/api/t/{slug}/admin/quizzes"
        val victim = mapOf("id" to ids.victimQuiz)
        return listOf(
            Probe(HttpMethod.GET, base, "一覧に出ない", status = 200),
            Probe(
                HttpMethod.GET,
                base,
                "victim のカテゴリで絞り込む",
                query = mapOf("categoryId" to ids.victimCategory, "difficultyId" to ids.victimDifficulty),
                status = 200,
            ),
            Probe(HttpMethod.GET, "$base/{id}", "", victim, status = 404, detail = QUIZ_NOT_FOUND),
            Probe(
                HttpMethod.POST,
                base,
                "victim のカテゴリに作る",
                body = quizBody(ids.victimCategory, ids.victimDifficulty),
                status = 404,
                detail = CATEGORY_NOT_FOUND,
                bodyIds = listOf(ids.victimCategory, ids.victimDifficulty),
            ),
            Probe(
                HttpMethod.POST,
                base,
                "自分のカテゴリに victim の難易度で作る",
                body = quizBody(ids.ownCategory, ids.victimDifficulty),
                status = 404,
                detail = DIFFICULTY_NOT_FOUND,
                bodyIds = listOf(ids.victimDifficulty),
            ),
            Probe(
                HttpMethod.PUT,
                "$base/{id}",
                "victim のクイズを書き換える",
                victim,
                body = quizBody(ids.ownCategory, ids.ownDifficulty),
                status = 404,
                detail = QUIZ_NOT_FOUND,
            ),
            Probe(
                HttpMethod.PUT,
                "$base/{id}",
                "自分のクイズを victim のカテゴリへ移す",
                mapOf("id" to ids.ownQuiz),
                body = quizBody(ids.victimCategory, ids.victimDifficulty),
                status = 404,
                detail = CATEGORY_NOT_FOUND,
                bodyIds = listOf(ids.victimCategory, ids.victimDifficulty),
            ),
            Probe(HttpMethod.DELETE, "$base/{id}", "", victim, status = 404, detail = QUIZ_NOT_FOUND),
        )
    }

    /** 取り込みはカテゴリと難易度を名前で指す。victim の名前は、自テナントの中では見つからない */
    private fun quizImportProbes(): List<Probe> {
        val base = "/api/t/{slug}/admin/quizzes/import"
        return listOf(
            Probe(
                HttpMethod.POST,
                base,
                "victim のカテゴリと難易度の名前で取り込む",
                body = importBody("$SECRET カテゴリ", "$SECRET 難易度"),
                status = 400,
                detail = IMPORT_REJECTED,
            ),
            Probe(
                HttpMethod.POST,
                base,
                "自分のカテゴリに victim の難易度の名前で取り込む",
                body = importBody("自分のカテゴリ", "$SECRET 難易度"),
                status = 400,
                detail = IMPORT_REJECTED,
            ),
        )
    }

    private fun trashProbes(ids: Ids): List<Probe> {
        val base = "/api/t/{slug}/admin/trash"
        return listOf(
            Probe(HttpMethod.GET, base, "削除済み一覧に出ない", status = 200),
            Probe(
                HttpMethod.POST,
                "$base/categories/{id}/restore",
                "",
                mapOf("id" to ids.deletedCategory),
                status = 404,
                detail = DELETED_NOT_FOUND,
            ),
            Probe(
                HttpMethod.POST,
                "$base/difficulties/{id}/restore",
                "",
                mapOf("id" to ids.deletedDifficulty),
                status = 404,
                detail = DELETED_NOT_FOUND,
            ),
            Probe(
                HttpMethod.POST,
                "$base/quizzes/{id}/restore",
                "",
                mapOf("id" to ids.deletedQuiz),
                status = 404,
                detail = DELETED_NOT_FOUND,
            ),
        )
    }

    private fun playCategoryProbes(): List<Probe> = listOf(
        Probe(HttpMethod.GET, "/api/t/{slug}/play/categories", "出題条件の選択肢に出ない", status = 200),
    )

    private fun attemptProbes(ids: Ids): List<Probe> {
        val base = "/api/t/{slug}/play/attempts"
        val victim = mapOf("attemptId" to ids.victimAttempt)
        return listOf(
            Probe(
                HttpMethod.POST,
                base,
                "victim のカテゴリで出題させる",
                body = """{"categoryId":"${ids.victimCategory}"}""",
                status = 422,
                bodyIds = listOf(ids.victimCategory),
            ),
            Probe(
                HttpMethod.POST,
                base,
                "victim の難易度で出題させる",
                body = """{"difficultyId":"${ids.victimDifficulty}"}""",
                status = 422,
                bodyIds = listOf(ids.victimDifficulty),
            ),
            // 攻撃者は victim で挑戦を中断している。それが own の「中断中」として返らないこと
            Probe(HttpMethod.GET, "$base/current", "victim の中断中の挑戦が返らない", status = 204),
            Probe(HttpMethod.GET, "$base/{attemptId}", "", victim, status = 404, detail = ATTEMPT_NOT_FOUND),
            Probe(
                HttpMethod.POST,
                "$base/{attemptId}/answers",
                "",
                victim,
                body = """{"quizId":"${ids.victimQuiz}","choiceId":"${ids.victimChoice}"}""",
                status = 404,
                detail = ATTEMPT_NOT_FOUND,
                bodyIds = listOf(ids.victimQuiz, ids.victimChoice),
            ),
            Probe(HttpMethod.POST, "$base/{attemptId}/complete", "", victim, status = 404, detail = ATTEMPT_NOT_FOUND),
            Probe(HttpMethod.POST, "$base/{attemptId}/abandon", "", victim, status = 404, detail = ATTEMPT_NOT_FOUND),
        )
    }

    private fun quizBody(categoryId: UUID, difficultyId: UUID): String {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        return """
            {"categoryId":"$categoryId","difficultyId":"$difficultyId",
             "question":"境界テスト","explanation":"解説","choices":[$choices],"status":"published"}
        """.trimIndent()
    }

    /** 送った名前を応答で繰り返すと、victim の文字列が含まれて漏洩と判定される。繰り返さないことも確かめている */
    private fun importBody(category: String, difficulty: String): String {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        return """
            {"quizzes":[{"category":"$category","difficulty":"$difficulty",
             "question":"境界テスト","explanation":"解説","choices":[$choices],"status":"published"}]}
        """.trimIndent()
    }

    // --- 準備 -----------------------------------------------------------------

    /**
     * 2 つのテナントにデータを用意する。**管理 API を通して作る**のは、
     * 本番と同じ経路で書き込まれた行に対して境界を確かめるため。
     */
    private fun prepare(): Ids {
        ownTenant = TestTenant.create("ノベンバー").withAdmin()
        victimTenant = TestTenant.create("オスカー").withAdmin()

        val ownFixture = PlayFixture(mockMvc, objectMapper, ownTenant.slug)
        val ownCategory = ownFixture.category("自分のカテゴリ")
        val ownDifficulty = ownFixture.difficulty(ownCategory, "自分の難易度", 1)
        val ownQuiz = ownFixture.quiz(ownCategory, ownDifficulty, "自分の問題")

        val victimFixture = PlayFixture(mockMvc, objectMapper, victimTenant.slug)
        val victimCategory = victimFixture.category("$SECRET カテゴリ")
        val victimDifficulty = victimFixture.difficulty(victimCategory, "$SECRET 難易度", 1)
        val victimQuiz = victimFixture.quiz(victimCategory, victimDifficulty, "$SECRET 問題")

        // 削除済み一覧に載せる。カテゴリを消すと難易度とクイズも連鎖して消える
        val deletedCategory = victimFixture.category("$SECRET 削除済みカテゴリ")
        val deletedDifficulty = victimFixture.difficulty(deletedCategory, "$SECRET 削除済み難易度", 1)
        val deletedQuiz = victimFixture.quiz(deletedCategory, deletedDifficulty, "$SECRET 削除済み問題")
        mockMvc.delete("/api/t/${victimTenant.slug}/admin/categories/$deletedCategory") {
            header("X-User-Id", TestAuth.ADMIN.toString())
        }.andExpect { status { isNoContent() } }

        // 攻撃者自身が victim で挑戦を中断している
        val attempt = mockMvc.post("/api/t/${victimTenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("X-User-Id", TestAuth.ADMIN.toString())
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)

        return Ids(
            ownCategory = ownCategory,
            ownDifficulty = ownDifficulty,
            ownQuiz = ownQuiz,
            victimCategory = victimCategory,
            victimDifficulty = victimDifficulty,
            victimQuiz = victimQuiz,
            victimChoice = UUID.fromString(attempt["quizzes"][0]["choices"][0]["id"].asString()),
            victimAttempt = UUID.fromString(attempt["id"].asString()),
            deletedCategory = deletedCategory,
            deletedDifficulty = deletedDifficulty,
            deletedQuiz = deletedQuiz,
        )
    }

    /** アプリケーションのエンドポイント。`GET /api/t/{slug}/admin/categories` の形で返す。 */
    private fun endpoints(): Set<String> = handlerMapping.handlerMethods
        .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.quizapp") }
        .flatMap { (info, _) ->
            info.methodsCondition.methods.flatMap { method -> info.patternValues.map { "${method.name} $it" } }
        }
        .toSet()

    /**
     * `tenant_id` を持つ全テーブルの、victim の行。
     *
     * テーブルは information_schema から引く。テーブルを足したときに書き換え検出の対象から漏れないようにする。
     * RLS を通さない所有者の接続で読む。
     */
    private fun victimSnapshot(): Map<String, String> = tenantTables().associateWith { table ->
        TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT coalesce(json_agg(t ORDER BY t::text), '[]')::text FROM $table t WHERE tenant_id = ?",
            String::class.java,
            victimTenant.id,
        ).orEmpty()
    }

    /** victim の行が持つ ID。応答に 1 つでも現れたら漏洩とみなす。 */
    private fun victimRowIds(): Set<UUID> = tenantTables()
        .filter { hasColumn(it, "id") }
        .flatMap { table ->
            TestPostgres.adminJdbcTemplate.queryForList(
                "SELECT id FROM $table WHERE tenant_id = ?",
                UUID::class.java,
                victimTenant.id,
            )
        }
        .filterNotNull()
        .toSet() + victimTenant.id

    private fun tenantTables(): List<String> = TestPostgres.adminJdbcTemplate.queryForList(
        """
        SELECT table_schema || '.' || table_name FROM information_schema.columns
        WHERE column_name = 'tenant_id' AND table_schema IN ('core', 'quiz', 'answer')
        ORDER BY 1
        """,
        String::class.java,
    ).filterNotNull()

    private fun hasColumn(table: String, column: String): Boolean = TestPostgres.adminJdbcTemplate.queryForObject(
        """
        SELECT count(*) > 0 FROM information_schema.columns
        WHERE table_schema || '.' || table_name = ? AND column_name = ?
        """,
        Boolean::class.java,
        table,
        column,
    ) == true

    /**
     * 1 件の検証ケース。
     *
     * @property vars `{slug}` 以外のパス変数。`{slug}` は常に攻撃者のテナント
     * @property bodyIds 本文やクエリで指定した victim の ID。応答に現れても漏洩とみなさない
     * @property detail 拒否の理由。ステータスだけでは、入力の不備による 404 / 400 と区別できない
     */
    private data class Probe(
        val method: HttpMethod,
        val path: String,
        val label: String,
        val vars: Map<String, UUID> = emptyMap(),
        val query: Map<String, UUID> = emptyMap(),
        val body: String? = null,
        val status: Int,
        val detail: String? = null,
        val bodyIds: List<UUID> = emptyList(),
    ) {
        val endpoint = "${method.name()} $path"
    }

    private data class Ids(
        val ownCategory: UUID,
        val ownDifficulty: UUID,
        val ownQuiz: UUID,
        val victimCategory: UUID,
        val victimDifficulty: UUID,
        val victimQuiz: UUID,
        val victimChoice: UUID,
        val victimAttempt: UUID,
        val deletedCategory: UUID,
        val deletedDifficulty: UUID,
        val deletedQuiz: UUID,
    ) {
        companion object {
            /** 網羅性の検査用。ケースの一覧が作れればよく、値は使わない */
            fun placeholder(): Ids = UUID(0, 0).let { Ids(it, it, it, it, it, it, it, it, it, it, it) }
        }
    }
}
