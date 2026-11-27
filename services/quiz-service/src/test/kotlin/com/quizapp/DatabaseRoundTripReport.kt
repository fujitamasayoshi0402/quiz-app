package com.quizapp

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.SqlStatementCounter
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 操作ごとの DB との往復の回数を表にして出す（DEV-235、ADR-0039）。DB の置き場所を変える案を比べるときに使う。
 *
 * prod と同じく読み取りをメモリに持つ設定で、2 回目の要求を数える。確かめるテストではないため、ふだんは流さない。
 * `DB_ROUND_TRIP_REPORT=1 ./gradlew :services:quiz-service:test --tests '*DatabaseRoundTripReport' -i` で流す
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_ROUND_TRIP_REPORT", matches = "1")
@AutoConfigureMockMvc
class DatabaseRoundTripReport {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            TestPostgres.configure(registry)
            registry.add("app.read-cache.enabled") { "true" }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun report() {
        val user = TestAuth.createUser("数える人")
        val tenant = TestTenant.create().withAdmin().join(user)
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        val category = fixture.category("カテゴリ")
        val difficulty = fixture.difficulty(category, "やさしい", 1)
        repeat(10) { fixture.quiz(category, difficulty, "問題 $it") }
        val auth = TestAuth.bearer(user)
        val t = "/api/t/${tenant.slug}"
        val rows = mutableListOf<String>()

        fun get(path: String) = mockMvc.get(path) {
            header("Authorization", auth)
        }.andExpect { status { is2xxSuccessful() } }
        fun post(path: String, body: String? = null) = mockMvc.post(path) {
            header("Authorization", auth)
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }.andExpect { status { is2xxSuccessful() } }.andReturn().response.contentAsString

        fun measure(name: String, warm: Boolean = true, block: () -> Unit) {
            if (warm) block()
            val statements = SqlStatementCounter.count(block)
            val trips = SqlStatementCounter.countRoundTrips(block)
            rows += "| $name | $statements | $trips |"
        }

        measure("所属するテナント GET /api/me/tenants") { get("/api/me/tenants") }
        measure("カテゴリの一覧") { get("$t/play/categories") }
        measure("中断中の挑戦（なし）") { get("$t/play/attempts/current") }
        measure("今日の復習") { get("$t/play/review") }
        measure("あとで見直す") { get("$t/play/bookmarks") }

        val start =
            """{"categoryId":"$category","scope":"all","order":"registered","limit":5,"discardInProgress":true}"""
        // 1 回目の「始める」で候補をメモリに持たせる
        post("$t/play/attempts", start)
        measure("始める（すべて。メモリ）", warm = false) { post("$t/play/attempts", start) }
        val unanswered = start.replace("\"all\"", "\"unanswered\"")
        measure("始める（未回答優先。DB）", warm = false) { post("$t/play/attempts", unanswered) }

        val attempt: JsonNode = objectMapper.readTree(post("$t/play/attempts", start))
        val id = attempt["id"].asString()
        Thread.sleep(500) // 裏の保存を終わらせる
        val quizzes = attempt["quizzes"].toList()
        var index = 0
        fun answerNext() {
            val quiz = quizzes[index++]
            post(
                "$t/play/attempts/$id/answers",
                """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}""",
            )
        }
        measure("回答（1 問）", warm = false) { answerNext() }
        measure("再開（挑戦を読む）") { get("$t/play/attempts/$id") }
        measure("完了（結果）", warm = false) { post("$t/play/attempts/$id/complete") }
        measure("履歴: 挑戦の一覧") { get("$t/play/history/attempts") }
        measure("履歴: カテゴリ別") { get("$t/play/history/categories") }
        measure("履歴: 解いた日") { get("$t/play/history/days") }
        measure("ランキング") { get("$t/play/ranking") }

        println("DEV-235-REPORT\n| 操作 | SQL の本数 | 往復の目安 |\n| --- | --- | --- |\n" + rows.joinToString("\n"))
    }
}
