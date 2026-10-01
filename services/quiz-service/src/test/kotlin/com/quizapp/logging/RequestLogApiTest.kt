package com.quizapp.logging

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestJwt
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * ログの形と、要求の文脈（開発ガイドライン「ログ」）。
 *
 * AWS と同じく JSON（ECS 形式）で出し、出たものを読んで確かめる。
 * - 要求の終わりに 1 行が出て、要求の ID・テナント・利用者・ルートの型・ステータス・時間を持つ
 * - 要求の途中のログにも、同じ文脈が載る
 * - **メールアドレス、トークン、招待のトークンを出さない**（Webhook の URL は `SlackWebhookApiTest` が見る）
 */
@SpringBootTest(properties = ["logging.structured.format.console=ecs"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class RequestLogApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    /** 出力のうち、JSON の行。ほかのテストのスレッドが出したものも混ざるため、要求の ID で絞って使う */
    private fun logLines(output: CapturedOutput): List<JsonNode> =
        output.out.lines().filter { it.startsWith("{") }.map { objectMapper.readTree(it) }

    private fun linesOf(output: CapturedOutput, requestId: String): List<JsonNode> =
        logLines(output).filter { it.at("/http/request/id").asString() == requestId }

    /** 要求の終わりの 1 行 */
    private fun completionOf(output: CapturedOutput, requestId: String): JsonNode =
        linesOf(output, requestId).single { it.at("/http/response/status_code").isNumber }

    private fun requestIdOf(result: MvcResult): String = result.response.getHeader(RequestLogFilter.HEADER)!!

    @Nested
    @DisplayName("要求の終わりの 1 行")
    inner class Completion {
        @Test
        @DisplayName("要求の ID・テナント・利用者・ルートの型・ステータス・かかった時間を持つ")
        fun hasRequestContext(output: CapturedOutput) {
            val tenant = TestTenant.create().withAdmin()

            val result = mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
                header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            }.andExpect { status { isOk() } }.andReturn()

            val line = completionOf(output, requestIdOf(result))
            assertThat(line.at("/tenant/id").asString()).isEqualTo(tenant.id.toString())
            assertThat(line.at("/user/id").asString()).isEqualTo(TestAuth.ADMIN.toString())
            assertThat(line.at("/http/request/method").asString()).isEqualTo("GET")
            assertThat(line.at("/http/route").asString()).isEqualTo("/api/t/{slug}/admin/categories")
            assertThat(line.at("/http/response/status_code").asInt()).isEqualTo(200)
            assertThat(line.at("/http/duration_ms").isNumber).isTrue()
            assertThat(line.at("/log/level").asString()).isEqualTo("INFO")
        }

        @Test
        @DisplayName("API Gateway が付けた要求の ID を引き継ぎ、応答のヘッダにも返す")
        fun reusesGatewayRequestId(output: CapturedOutput) {
            val gatewayId = "Kx1q3hXYtjMEJ5A="

            val result = mockMvc.get("/api/me/tenants") {
                header("Authorization", TestAuth.bearer(TestAuth.OUTSIDER))
                header(RequestLogFilter.HEADER, gatewayId)
            }.andExpect { status { isOk() } }.andReturn()

            assertThat(requestIdOf(result)).isEqualTo(gatewayId)
            assertThat(completionOf(output, gatewayId).at("/user/id").asString())
                .isEqualTo(TestAuth.OUTSIDER.toString())
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "has space", "改行\nを含む", "<script>"])
        @DisplayName("形のおかしい要求の ID は使わず、作り直す")
        fun replacesInvalidRequestId(given: String) {
            val result = mockMvc.get("/api/me/tenants") {
                header(RequestLogFilter.HEADER, given)
            }.andReturn()

            assertThat(requestIdOf(result)).isNotEqualTo(given).matches("[0-9a-f-]{36}")
        }

        @Test
        @DisplayName("ヘルスチェック（/api の外）では出さない")
        fun skipsOutsideApi(output: CapturedOutput) {
            val result = mockMvc.get("/actuator/health").andExpect { status { isOk() } }.andReturn()

            assertThat(linesOf(output, requestIdOf(result))).isEmpty()
        }
    }

    @Test
    @DisplayName("要求の途中のログにも、要求の ID・テナント・利用者が載る")
    fun logsInsideRequestCarryContext(output: CapturedOutput) {
        val tenant = TestTenant.create()

        // 所属していないテナントへのアクセスは、拒否したことを警告で残す（ApiExceptionHandler）
        val result = mockMvc.get("/api/t/${tenant.slug}/play/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.OUTSIDER))
        }.andExpect { status { isNotFound() } }.andReturn()

        val warning = linesOf(output, requestIdOf(result)).single { it.at("/log/level").asString() == "WARN" }
        assertThat(warning.at("/tenant/id").asString()).isEqualTo(tenant.id.toString())
        assertThat(warning.at("/user/id").asString()).isEqualTo(TestAuth.OUTSIDER.toString())
    }

    @Test
    @DisplayName("メールアドレス、アクセストークン、招待のトークンを出さない。ルートは型で出す")
    fun doesNotLogSecrets(output: CapturedOutput) {
        // 初めて見る利用者は、確認済みのメールアドレスを取って作る。作ったことをログに残す（CognitoAuthenticator）
        val email = "log-${UUID.randomUUID()}@example.test"
        val accessToken = TestJwt.issue("sub-${UUID.randomUUID()}", email = email)
        val invitationToken = "invitation-${UUID.randomUUID()}"

        val result = mockMvc.get("/api/me/invitations/$invitationToken") {
            header("Authorization", "Bearer $accessToken")
        }.andReturn()

        val lines = linesOf(output, requestIdOf(result))
        assertThat(lines.map { it.at("/message").asString() }).anyMatch { it.startsWith("利用者を作りました") }
        assertThat(completionOf(output, requestIdOf(result)).at("/http/route").asString())
            .isEqualTo("/api/me/invitations/{token}")
        assertThat(output.out).doesNotContain(email, accessToken, invitationToken)
    }
}
