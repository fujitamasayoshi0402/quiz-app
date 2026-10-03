package com.quizapp.tracing

import com.quizapp.logging.RequestLogFilter
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import com.quizapp.support.eventually
import com.quizapp.support.fake.InMemoryEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 分散トレース（ADR-0026）。
 * - 呼び出し元（web の proxy）の `traceparent` を引き継ぎ、ログの各行に trace ID が載る
 * - 無ければ、X-Ray が受け付ける形の trace ID を作る
 * - クイズの変更で書いたイベントに、同じトレースの文脈が残る
 */
@SpringBootTest(properties = ["logging.structured.format.console=ecs"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class TracingApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        const val TRACE_ID = "6a1f3c2b0123456789abcdef01234567"
        const val TRACE_PARENT = "00-$TRACE_ID-0123456789abcdef-01"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var bus: InMemoryEventBus

    private fun completionOf(output: CapturedOutput, requestId: String): JsonNode = output.out.lines()
        .filter { it.startsWith("{") }
        .map { objectMapper.readTree(it) }
        .single { it.at("/http/request/id").asString() == requestId && it.at("/http/response/status_code").isNumber }

    @Test
    @DisplayName("呼び出し元の traceparent を引き継ぎ、ログに trace ID と span ID を載せる")
    fun continuesCallerTrace(output: CapturedOutput) {
        val tenant = TestTenant.create().withAdmin()

        val result = mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            header("traceparent", TRACE_PARENT)
        }.andExpect { status { isOk() } }.andReturn()

        val line = completionOf(output, result.response.getHeader(RequestLogFilter.HEADER)!!)
        assertThat(line["trace.id"].asString()).isEqualTo(TRACE_ID)
        assertThat(line["span.id"].asString()).matches("[0-9a-f]{16}").isNotEqualTo("0123456789abcdef")
    }

    @Test
    @DisplayName("traceparent が無ければ、先頭が時刻の trace ID を作る")
    fun startsXrayCompatibleTrace(output: CapturedOutput) {
        val tenant = TestTenant.create().withAdmin()

        val result = mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }.andReturn()

        val traceId = completionOf(output, result.response.getHeader(RequestLogFilter.HEADER)!!)["trace.id"].asString()
        assertThat(traceId).matches("[0-9a-f]{32}")
        assertThat(traceId.take(8).toLong(16)).isBetween(Instant.now().epochSecond - 60, Instant.now().epochSecond)
    }

    @Test
    @DisplayName("クイズの変更で書いたイベントに、同じトレースの文脈が残る")
    fun eventCarriesTrace() {
        val tenant = TestTenant.create().withAdmin()
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        val category = fixture.category("AWS")
        val difficulty = fixture.difficulty(category, "SAA", 2)
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }

        mockMvc.post("/api/t/${tenant.slug}/admin/quizzes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$category","difficultyId":"$difficulty","question":"問題文","explanation":"解説",
                 "choices":[$choices],"status":"published"}
            """.trimIndent()
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            header("traceparent", TRACE_PARENT)
        }.andExpect { status { isCreated() } }

        eventually {
            val sent = bus.published.single { it.tenantId == tenant.id }
            assertThat(sent.traceParent).matches("00-$TRACE_ID-[0-9a-f]{16}-01")
        }
        val stored = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT trace_parent FROM quiz.outbox WHERE tenant_id = ?",
            String::class.java,
            tenant.id,
        )
        assertThat(stored).startsWith("00-$TRACE_ID-")
    }
}
