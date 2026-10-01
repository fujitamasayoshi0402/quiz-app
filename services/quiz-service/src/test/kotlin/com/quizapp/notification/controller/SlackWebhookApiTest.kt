package com.quizapp.notification.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import com.quizapp.support.fake.InMemorySlackWebhookStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * テナントの Slack の通知先（ADR-0022）。
 *
 * 要点は 2 つ。
 * - **設定した URL は、API の応答にもログにも出ない。** 置き場所（SSM）には届いている
 * - `https://hooks.slack.com/` の下を指さない URL は 400 で、どこにも残らない
 *
 * 別のテナントの設定に触れないことは `TenantBoundaryApiTest`、一般ユーザーが触れないことはパスの規約が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class SlackWebhookApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SECRET = "WEBHOOK-SECRET"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var store: InMemorySlackWebhookStore

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin().join(TestAuth.MEMBER)
    }

    @AfterEach
    fun tearDown() {
        store.failing = false
    }

    @Test
    @DisplayName("設定していなければ、設定していないと返す")
    fun notConfiguredByDefault() {
        val body = get().andExpect { status { isOk() } }.json()

        assertThat(body["configured"].asBoolean()).isFalse()
        assertThat(body.has("configuredAt")).isFalse()
    }

    @Test
    @DisplayName("設定すると SSM に URL が置かれ、応答は設定したかどうかと日時だけを返す")
    fun configures(output: CapturedOutput) {
        val url = webhook()

        val configured = configure(url).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val fetched = get().andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertThat(store.find(tenant.id)).isEqualTo(url)
        for (body in listOf(configured, fetched)) {
            val json = objectMapper.readTree(body)
            assertThat(json["configured"].asBoolean()).isTrue()
            assertThat(json["configuredAt"].asString()).isNotBlank()
            assertThat(body).doesNotContain(SECRET).doesNotContain("hooks.slack.com")
        }
        assertThat(output.all).doesNotContain(SECRET)
    }

    @Test
    @DisplayName("設定し直すと、URL を置き換える")
    fun replaces() {
        configure(webhook()).andExpect { status { isOk() } }
        val replaced = webhook()

        configure(replaced).andExpect { status { isOk() } }

        assertThat(store.find(tenant.id)).isEqualTo(replaced)
    }

    @Test
    @DisplayName("消すと SSM からも消え、設定していない状態に戻る。設定していなくても消せる")
    fun removes() {
        configure(webhook()).andExpect { status { isOk() } }

        remove().andExpect { status { isNoContent() } }

        assertThat(store.find(tenant.id)).isNull()
        assertThat(get().json()["configured"].asBoolean()).isFalse()
        remove().andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("hooks.slack.com の下を指さない URL は 400 で、どこにも残さず、応答とログにも出さない")
    fun rejectsOtherUrls(output: CapturedOutput) {
        for (url in listOf(
            "https://example.test/$SECRET",
            "https://hooks.slack.com.example.test/services/$SECRET",
            "https://hooks.slack.com@example.test/services/$SECRET",
            "http://hooks.slack.com/services/$SECRET",
            "",
        )) {
            val body = configure(url)
                .andExpect { status { isBadRequest() } }
                .andExpect { jsonPath("$.errors.url") { exists() } }
                .andReturn().response.contentAsString
            assertThat(body).doesNotContain(SECRET)
        }

        assertThat(store.find(tenant.id)).isNull()
        assertThat(get().json()["configured"].asBoolean()).isFalse()
        assertThat(output.all).doesNotContain(SECRET)
    }

    @Test
    @DisplayName("SSM に置けなければ、設定したという印も残らない")
    fun rollsBackWhenStoreFails() {
        store.failing = true

        configure(webhook()).andExpect { status { isServiceUnavailable() } }

        store.failing = false
        assertThat(get().json()["configured"].asBoolean()).isFalse()
    }

    @Test
    @DisplayName("SSM から消せなければ、設定済みのまま残す。URL が残っているのに、設定していないと見せない")
    fun keepsSettingWhenRemoveFails() {
        configure(webhook()).andExpect { status { isOk() } }
        store.failing = true

        remove().andExpect { status { isServiceUnavailable() } }

        store.failing = false
        assertThat(get().json()["configured"].asBoolean()).isTrue()
    }

    @Test
    @DisplayName("一般ユーザーは、見ることも設定することもできない")
    fun membersCannotAccess() {
        get(TestAuth.MEMBER).andExpect { status { isForbidden() } }
        configure(webhook(), TestAuth.MEMBER).andExpect { status { isForbidden() } }
        remove(TestAuth.MEMBER).andExpect { status { isForbidden() } }

        assertThat(store.find(tenant.id)).isNull()
    }

    private fun webhook() = "https://hooks.slack.com/services/T0/B0/$SECRET-${UUID.randomUUID()}"

    private fun base() = "/api/t/${tenant.slug}/admin/notifications/slack"

    private fun get(user: UUID = TestAuth.ADMIN) = mockMvc.get(base()) {
        header("Authorization", TestAuth.bearer(user))
    }

    private fun configure(url: String, user: UUID = TestAuth.ADMIN) = mockMvc.put(base()) {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("url" to url))
        header("Authorization", TestAuth.bearer(user))
    }

    private fun remove(user: UUID = TestAuth.ADMIN) = mockMvc.delete(base()) {
        header("Authorization", TestAuth.bearer(user))
    }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
