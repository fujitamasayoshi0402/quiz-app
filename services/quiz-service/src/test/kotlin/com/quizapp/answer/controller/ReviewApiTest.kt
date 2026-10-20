package com.quizapp.answer.controller

import com.quizapp.answer.domain.StudyDay
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * 今日の復習（DEV-178）。次に出す日の式は [com.quizapp.answer.domain.ReviewScheduleTest] が見る。
 *
 * ここで見るのは、SQL と、回答から出題までのつながり。
 * - 間違えた問題が今日の復習に出て、出題対象 `review` で解ける。正解すると次の日へ延びる
 * - 日が過ぎると、また今日の復習に出る
 * - 削除・非公開のクイズを数えない
 * - 別のテナントの予定を数えない
 *
 * テナントの境界は [com.quizapp.tenant.TenantBoundaryApiTest]、退会で消えることは TenantLeavingApiTest が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val today: LocalDate get() = StudyDay.today(Clock.systemUTC())

    @Test
    @DisplayName("間違えた問題は今日の復習に出て、そこから解ける。正解すると、次に出る日が明日になる")
    fun missedQuizComesBackToday() {
        val play = Play(TestTenant.create().withAdmin())
        val (missed) = play.quizzes(2)
        val user = play.member()
        play.solve(user, "all", false, true)

        assertThat(play.summary(user)["dueCount"].asInt()).isEqualTo(1)

        val attempt = play.start(user, "review")
        assertThat(attempt["quizzes"].toList().map { UUID.fromString(it["id"].asString()) }).containsExactly(missed)
        play.answer(user, attempt, 0, correct = true)

        val summary = play.summary(user)
        assertThat(summary["dueCount"].asInt()).isZero()
        assertThat(summary["nextDueOn"].asString()).isEqualTo(today.plusDays(1).toString())
        assertThat(summary["today"].asString()).isEqualTo(today.toString())
    }

    @Test
    @DisplayName("次に出す日が来ると、また今日の復習に出る。期日に正解すると 6 日後へ延びる")
    fun dueQuizComesBackWhenTheDayComes() {
        val play = Play(TestTenant.create().withAdmin())
        play.quizzes(1)
        val user = play.member()
        play.solve(user, "all", true)
        assertThat(play.summary(user)["dueCount"].asInt()).isZero()

        // 1 日たったことにする
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE answer.review_schedules SET due_on = due_on - 1 WHERE user_id = ?",
            user,
        )
        assertThat(play.summary(user)["dueCount"].asInt()).isEqualTo(1)

        play.solve(user, "review", true)
        assertThat(play.summary(user)["nextDueOn"].asString()).isEqualTo(today.plusDays(6).toString())
    }

    @Test
    @DisplayName("期日の前に正解しても、次に出る日は変わらない")
    fun earlyCorrectAnswerDoesNotStretch() {
        val play = Play(TestTenant.create().withAdmin())
        play.quizzes(1)
        val user = play.member()
        play.solve(user, "all", true)
        play.solve(user, "all", true)

        assertThat(play.summary(user)["nextDueOn"].asString()).isEqualTo(today.plusDays(1).toString())
    }

    @Test
    @DisplayName("削除したクイズの予定は数えない。復習を待つ問題がなければ、次の日は null")
    fun ignoresDeletedQuizzes() {
        val play = Play(TestTenant.create().withAdmin())
        val (quiz) = play.quizzes(1)
        val user = play.member()
        play.solve(user, "all", false)
        assertThat(play.summary(user)["dueCount"].asInt()).isEqualTo(1)

        mockMvc.delete("/api/t/${play.tenant.slug}/admin/quizzes/$quiz") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        val summary = play.summary(user)
        assertThat(summary["dueCount"].asInt()).isZero()
        // null の項目は応答に出ない
        assertThat(summary["nextDueOn"]?.takeUnless { it.isNull }).isNull()
    }

    @Test
    @DisplayName("別のテナントで間違えた問題は、このテナントの復習に数えない")
    fun ignoresOtherTenants() {
        val user = TestAuth.createUser()
        val here = Play(TestTenant.create().withAdmin().join(user))
        val there = Play(TestTenant.create().withAdmin().join(user))
        here.quizzes(1)
        there.quizzes(1)

        there.solve(user, "all", false)

        assertThat(here.summary(user)["dueCount"].asInt()).isZero()
        assertThat(there.summary(user)["dueCount"].asInt()).isEqualTo(1)
    }

    private inner class Play(val tenant: TestTenant) {
        private val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        private val category = fixture.category("AWS")
        private val difficulty = fixture.difficulty(category, "SAA", 2)

        fun quizzes(count: Int): List<UUID> = (1..count).map { fixture.quiz(category, difficulty, "問題 $it") }

        fun member(): UUID = TestAuth.createUser().also { tenant.join(it) }

        fun start(userId: UUID, scope: String): JsonNode = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"$scope","order":"registered","discardInProgress":true}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)

        /** [index] 番目の問題に答える。[PlayFixture] は「選択肢 1」を正解にする。選択肢は並びが変わるため、本文で選ぶ */
        fun answer(userId: UUID, attempt: JsonNode, index: Int, correct: Boolean) {
            val quiz = attempt["quizzes"][index]
            val choice = quiz["choices"].first { (it["body"].asString() == "選択肢 1") == correct }
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/answers") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${choice["id"].asString()}"}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
        }

        /** 登録順に出題し、先頭から [results] の数だけ答えて終える。true なら正解を選ぶ */
        fun solve(userId: UUID, scope: String, vararg results: Boolean) {
            val attempt = start(userId, scope)
            results.forEachIndexed { index, correct -> answer(userId, attempt, index, correct) }
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/complete") {
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
        }

        fun summary(userId: UUID): JsonNode = mockMvc.get("/api/t/${tenant.slug}/play/review") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
    }
}
