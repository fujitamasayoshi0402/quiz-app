package com.quizapp.answer.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * ランキングの統合テスト。
 *
 * SQL が担うことをここで見る。
 * - **参加した人だけが載ること。** 参加していない人、所属から外れた人は載らない
 * - 正解したクイズの数で並べ、同じクイズは期間内で最新の回答で 1 回だけ数えること
 * - 同点は正答率の高い順、それも同じなら同じ順位になること
 * - 期間の区切りと、削除・非公開のクイズを数えないこと
 * - 名前がテナントの中で重ならないこと
 *
 * テナントの境界は [com.quizapp.tenant.TenantBoundaryApiTest] が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RankingApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    // --- 並び -----------------------------------------------------------------

    @Test
    @DisplayName("参加した人だけを、正解したクイズの数の順に載せる")
    fun ranksParticipantsByCorrectCount() {
        quizzes(3)
        val alice = member().also { participate(it, "alice") }
        val bob = member().also { participate(it, "bob") }
        val outsider = member()
        solve(alice, true, true, false)
        solve(bob, true, false, false)
        // 参加していない人は、いちばん成績が良くても載らない
        solve(outsider, true, true, true)

        val view = ranking(alice)

        assertThat(view.rows()).containsExactly(
            Row(1, "alice", correct = 2, answered = 3, isMe = true),
            Row(2, "bob", correct = 1, answered = 3, isMe = false),
        )
        assertThat(view["me"]["name"].asString()).isEqualTo("alice")
        assertThat(view["me"]["entry"]["rank"].asInt()).isEqualTo(1)
    }

    @Test
    @DisplayName("同じクイズは、期間内で最新の回答で 1 回だけ数える")
    fun countsLatestAnswerOnce() {
        quizzes(1)
        val alice = member().also { participate(it, "alice") }

        solve(alice, true)
        solve(alice, true)
        assertThat(ranking(alice).rows().single()).isEqualTo(Row(1, "alice", correct = 1, answered = 1, isMe = true))

        // 最新が誤りなら数えない
        solve(alice, false)
        assertThat(ranking(alice).rows().single()).isEqualTo(Row(1, "alice", correct = 0, answered = 1, isMe = true))
    }

    @Test
    @DisplayName("同点は正答率の高いほうが上。それも同じなら同じ順位で、次の順位は人数分とぶ")
    fun breaksTiesByAccuracy() {
        quizzes(3)
        val users = listOf("alice", "bob", "carol", "dave").associateWith {
            member().also { id -> participate(id, it) }
        }
        solve(users.getValue("alice"), true, true)
        solve(users.getValue("bob"), true, true, false)
        solve(users.getValue("carol"), true, true, false)
        solve(users.getValue("dave"), true, false, false)

        val ranks = ranking(users.getValue("alice")).rows().map { it.rank to it.name }

        assertThat(ranks).containsExactly(1 to "alice", 2 to "bob", 2 to "carol", 4 to "dave")
    }

    // --- 数える範囲 -----------------------------------------------------------

    @Test
    @DisplayName("期間より前の回答は数えない")
    fun limitsToPeriod() {
        quizzes(1)
        val alice = member().also { participate(it, "alice") }
        solve(alice, true)
        backdate(alice, days = 10)

        assertThat(ranking(alice, "7d").rows()).isEmpty()
        assertThat(ranking(alice, "30d").rows()).hasSize(1)

        backdate(alice, days = 40)
        assertThat(ranking(alice, "30d").rows()).isEmpty()
        assertThat(ranking(alice, "all").rows()).hasSize(1)
        // 期間内に解いていなくても、参加していることは分かる
        assertThat(ranking(alice, "7d")["me"]["name"].asString()).isEqualTo("alice")
    }

    @Test
    @DisplayName("削除・下書きにしたクイズへの回答は数えない")
    fun excludesWithdrawnQuizzes() {
        val deleted = quizzes(2).first()
        val alice = member().also { participate(it, "alice") }
        solve(alice, true, true)

        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$deleted") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        assertThat(ranking(alice).rows().single()).isEqualTo(Row(1, "alice", correct = 1, answered = 1, isMe = true))
    }

    @Test
    @DisplayName("所属から外れた人は載らない")
    fun excludesFormerMembers() {
        quizzes(1)
        val alice = member().also { participate(it, "alice") }
        val bob = member().also { participate(it, "bob") }
        solve(alice, true)
        solve(bob, true)

        TestPostgres.adminJdbcTemplate.update(
            "UPDATE core.tenant_members SET deleted_at = now() WHERE tenant_id = ? AND user_id = ?",
            tenant.id,
            bob,
        )

        assertThat(ranking(alice).rows().map { it.name }).containsExactly("alice")
    }

    @Test
    @DisplayName("参加していない人も見られる。自分の名前と順位は返らない")
    fun nonParticipantCanView() {
        quizzes(1)
        val alice = member().also { participate(it, "alice") }
        solve(alice, true)
        val viewer = member()

        val view = ranking(viewer)

        assertThat(view.rows().map { it.name to it.isMe }).containsExactly("alice" to false)
        assertThat(view["me"].has("name")).isFalse()
        assertThat(view["me"].has("entry")).isFalse()
    }

    // --- 参加 -----------------------------------------------------------------

    @Test
    @DisplayName("参加して名前を変え、やめられる。前後の空白は落とす")
    fun participatesRenamesAndLeaves() {
        val alice = member()

        participate(alice, "  alice ").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("alice") }
        }
        participate(alice, "alice2").andExpect { status { isOk() } }
        assertThat(ranking(alice)["me"]["name"].asString()).isEqualTo("alice2")

        mockMvc.delete("/api/t/${tenant.slug}/play/ranking/participation") {
            header("Authorization", TestAuth.bearer(alice))
        }.andExpect { status { isNoContent() } }
        assertThat(ranking(alice)["me"].has("name")).isFalse()
    }

    @Test
    @DisplayName("同じテナントで使われている名前は、大文字と小文字が違っても 409。別のテナントなら使える")
    fun namesAreUniqueWithinTenant() {
        participate(member(), "Alice").andExpect { status { isOk() } }

        participate(member(), "alice").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("その名前は、このテナントですでに使われています") }
        }

        val other = TestTenant.create("別のテナント")
        val elsewhere = TestAuth.createUser().also { other.join(it) }
        mockMvc.put("/api/t/${other.slug}/play/ranking/participation") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Alice"}"""
            header("Authorization", TestAuth.bearer(elsewhere))
        }.andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("空白だけの名前、長すぎる名前、知らない期間は 400")
    fun rejectsInvalidInput() {
        val alice = member()
        participate(alice, "   ").andExpect { status { isBadRequest() } }
        participate(alice, "あ".repeat(21)).andExpect { status { isBadRequest() } }
        mockMvc.get("/api/t/${tenant.slug}/play/ranking") {
            param("period", "1y")
            header("Authorization", TestAuth.bearer(alice))
        }.andExpect { status { isBadRequest() } }
    }

    // --- ヘルパー -------------------------------------------------------------

    private data class Row(val rank: Int, val name: String, val correct: Int, val answered: Int, val isMe: Boolean)

    private fun JsonNode.rows(): List<Row> = this["entries"].values().map {
        Row(
            it["rank"].asInt(),
            it["name"].asString(),
            it["correctCount"].asInt(),
            it["answeredCount"].asInt(),
            it["isMe"].asBoolean(),
        )
    }

    private fun quizzes(count: Int): List<UUID> = (1..count).map { fixture.quiz(category, difficulty, "問題 $it") }

    private fun member(): UUID = TestAuth.createUser().also { tenant.join(it) }

    private fun participate(userId: UUID, name: String): ResultActionsDsl =
        mockMvc.put("/api/t/${tenant.slug}/play/ranking/participation") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("name" to name))
            header("Authorization", TestAuth.bearer(userId))
        }

    /** 登録順に出題し、先頭から [results] の数だけ答えて終える。true なら正解を選ぶ */
    private fun solve(userId: UUID, vararg results: Boolean) {
        val attempt = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all","order":"registered"}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
        val attemptId = attempt["id"].asString()

        results.forEachIndexed { index, correct ->
            val quiz = attempt["quizzes"][index]
            // [PlayFixture] は「選択肢 1」を正解にする。選択肢は並びが変わるため、本文で選ぶ
            val choice = quiz["choices"].first { (it["body"].asString() == "選択肢 1") == correct }
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${choice["id"].asString()}"}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
        }
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/complete") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
    }

    /** 利用者の回答を、[days] 日前にしたことにする */
    private fun backdate(userId: UUID, days: Int) {
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE answer.answers SET answered_at = now() - make_interval(days => ?) WHERE user_id = ?",
            days,
            userId,
        )
    }

    private fun ranking(userId: UUID, period: String? = null): JsonNode =
        mockMvc.get("/api/t/${tenant.slug}/play/ranking") {
            period?.let { param("period", it) }
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
}
