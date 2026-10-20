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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 学習の記録（DEV-179）。続けて解いた日の数え方は [com.quizapp.answer.domain.StudyStreakTest] が見る。
 *
 * ここで見るのは SQL。
 * - **日の区切りは日本時間の 0 時。** その前後の回答は、別の日に数える
 * - 終えていない挑戦の回答も数える
 * - 直近 12 週より前の日は返さない
 *
 * テナントの境界は [com.quizapp.tenant.TenantBoundaryApiTest]、退会で消えることは TenantLeavingApiTest、
 * 回答の数によって SQL が増えないことは [com.quizapp.QueryCountApiTest] が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudyDaysApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val today: LocalDate get() = StudyDay.today(Clock.systemUTC())

    /** 今日の日本時間の 0 時 */
    private val midnight: Instant get() = today.atStartOfDay(StudyDay.ZONE).toInstant()

    @Test
    @DisplayName("日本時間の 0 時の前と後の回答は、別の日に数える。昨日から続けて解いていれば 2 日")
    fun splitsDaysAtMidnightInJapan() {
        val play = Play()
        val user = play.member()
        val (before, after) = play.answer(user, 2)
        play.answeredAt(before, midnight.minusSeconds(1))
        play.answeredAt(after, midnight)

        val days = play.days(user)

        assertThat(days.counts()).containsExactly(today.minusDays(1) to 1, today to 1)
        assertThat(days["streak"].asInt()).isEqualTo(2)
        assertThat(days["today"].asString()).isEqualTo(today.toString())
    }

    @Test
    @DisplayName("終えていない挑戦の回答も、その日に数える")
    fun countsAnswersOfUnfinishedAttempts() {
        val play = Play()
        val user = play.member()
        play.answer(user, 2)

        val days = play.days(user)

        assertThat(days.counts()).containsExactly(today to 2)
        assertThat(days["streak"].asInt()).isEqualTo(1)
    }

    @Test
    @DisplayName("直近 12 週より前の日は返さない。続いていなければ、連続日数は 0")
    fun limitsToRecentWeeks() {
        val play = Play()
        val user = play.member()
        val (old, recent) = play.answer(user, 2)
        play.answeredAt(old, midnight.minusSeconds(100L * 24 * 60 * 60))
        play.answeredAt(recent, midnight.minusSeconds(3L * 24 * 60 * 60))

        val days = play.days(user)

        assertThat(days.counts()).containsExactly(today.minusDays(3) to 1)
        assertThat(days["streak"].asInt()).isZero()
    }

    private fun JsonNode.counts(): List<Pair<LocalDate, Int>> =
        this["days"].toList().map { LocalDate.parse(it["date"].asString()) to it["count"].asInt() }

    private inner class Play {
        private val tenant = TestTenant.create().withAdmin()
        private val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)

        init {
            val category = fixture.category("AWS")
            val difficulty = fixture.difficulty(category, "SAA", 2)
            repeat(2) { fixture.quiz(category, difficulty, "問題 $it") }
        }

        fun member(): UUID = TestAuth.createUser().also { tenant.join(it) }

        /** 挑戦を始め、[count] 問に答える。終えない。返すのは回答の ID */
        fun answer(userId: UUID, count: Int): List<UUID> {
            val attempt = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"scope":"all","order":"registered"}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
            val attemptId = attempt["id"].asString()

            attempt["quizzes"].toList().take(count).forEach { quiz ->
                mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""
                    header("Authorization", TestAuth.bearer(userId))
                }.andExpect { status { isOk() } }
            }
            return TestPostgres.adminJdbcTemplate.queryForList(
                "SELECT id FROM answer.answers WHERE attempt_id = ? ORDER BY answered_at, id",
                UUID::class.java,
                UUID.fromString(attemptId),
            ).filterNotNull()
        }

        fun answeredAt(answerId: UUID, at: Instant) {
            TestPostgres.adminJdbcTemplate.update(
                "UPDATE answer.answers SET answered_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                answerId,
            )
        }

        fun days(userId: UUID): JsonNode = mockMvc.get("/api/t/${tenant.slug}/play/history/days") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
    }
}
