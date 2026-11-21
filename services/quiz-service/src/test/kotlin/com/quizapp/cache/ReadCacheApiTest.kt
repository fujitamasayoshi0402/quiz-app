package com.quizapp.cache

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.SqlStatementCounter
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 出題の読み取りをメモリに持つ（ADR-0038）。**DB が止まっていても、出題の画面の一覧と図をすぐ返す。**
 *
 * DB を止める代わりに、2 回目の要求が SQL を 1 本も送らないことを確かめる。利用者・テナント・所属の確認も含めて、
 * 1 本でも送れば、止まっている DB の復帰を待つことになる。
 *
 * ほかのテストは、メモリに持たない設定で動く（[TestPostgres.configure]）。ここだけ持たせる
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadCacheApiTest {

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
    @DisplayName("2 回目からは、カテゴリの一覧と図を、SQL を送らずに返す")
    fun servesPlayReadsWithoutDatabase() {
        val user = TestAuth.createUser("見に来た人")
        val tenant = TestTenant.create().withAdmin().join(user)
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        val category = fixture.category("カテゴリ")
        fixture.quiz(category, fixture.difficulty(category, "やさしい", 1), "問題")
        val figure = fixture.figure()

        categories(tenant, user)
        preview(tenant, user, figure)

        assertThat(SqlStatementCounter.count { categories(tenant, user) }).isZero()
        assertThat(SqlStatementCounter.count { preview(tenant, user, figure) }).isZero()
    }

    @Test
    @DisplayName("管理の API で内容を変えたら、次の一覧に出る")
    fun forgetsContentOnAdminChange() {
        val user = TestAuth.createUser("見に来た人")
        val tenant = TestTenant.create().withAdmin().join(user)
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        val category = fixture.category("カテゴリ")
        val difficulty = fixture.difficulty(category, "やさしい", 1)
        fixture.quiz(category, difficulty, "1 問目")
        assertThat(categories(tenant, user)[0]["quizCount"].asInt()).isEqualTo(1)

        fixture.quiz(category, difficulty, "2 問目")

        assertThat(categories(tenant, user)[0]["quizCount"].asInt()).isEqualTo(2)
    }

    @Test
    @DisplayName("退会したら、メモリに持った所属で読めない")
    fun forgetsMembershipOnLeave() {
        val user = TestAuth.createUser("退会する人")
        val tenant = TestTenant.create().withAdmin().join(user)
        categories(tenant, user)

        mockMvc.delete("/api/t/${tenant.slug}/play/membership") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/t/${tenant.slug}/play/categories") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isNotFound() } }
    }

    private fun categories(tenant: TestTenant, user: UUID): JsonNode {
        val result = mockMvc.get("/api/t/${tenant.slug}/play/categories") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun preview(tenant: TestTenant, user: UUID, figure: UUID) {
        mockMvc.get("/api/t/${tenant.slug}/play/figures/$figure/preview") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isFound() } }
    }
}
