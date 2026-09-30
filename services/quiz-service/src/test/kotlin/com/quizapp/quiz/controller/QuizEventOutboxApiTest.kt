package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.quiz.usecase.QuizUseCase
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantSession
import com.quizapp.tenant.TenantTransaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * クイズの操作が、変更と同じトランザクションで Outbox に書かれるか（ADR-0022）。
 *
 * どの操作でどのイベントかの細かい分岐は `QuizEventUseCaseTest`、JSON の形は `QuizEventSamplesTest` が見る。
 * ここで見るのは DB でしか確かめられないこと。
 * - 操作が失敗したら、クイズもイベントも残らない
 * - Outbox の行もテナントで分かれ、拾い直し（DEV-97）の印を立てたときだけテナントをまたいで読める
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizEventOutboxApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var tenantSession: TenantSession

    @Autowired private lateinit var tenantTransaction: TenantTransaction

    @Autowired private lateinit var quizUseCase: QuizUseCase

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("ホテル").withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private data class OutboxRow(val eventType: String, val payload: JsonNode, val tenantId: UUID)

    /** 行レベルセキュリティを通さずに読む。アプリから見えるかどうかは、別のテストで見る */
    private fun outbox(tenantId: UUID = tenant.id): List<OutboxRow> = TestPostgres.adminJdbcTemplate.query(
        "SELECT event_type, payload::text, tenant_id FROM quiz.outbox WHERE tenant_id = ? ORDER BY occurred_at, id",
        { rs, _ ->
            OutboxRow(rs.getString(1), objectMapper.readTree(rs.getString(2)), rs.getObject(3, UUID::class.java))
        },
        tenantId,
    )

    private fun quizCount(): Int? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM quiz.quizzes WHERE tenant_id = ?",
        Int::class.java,
        tenant.id,
    )

    private fun save(id: UUID, question: String, status: String) {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        mockMvc.put("/api/t/${tenant.slug}/admin/quizzes/$id") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$category","difficultyId":"$difficulty",
                 "question":"$question","explanation":"$question の解説",
                 "choices":[$choices],"status":"$status"}
            """.trimIndent()
        }.andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("作成・更新・公開・取り下げ・一括インポートで、1 件ずつ書かれる")
    fun eachOperationWritesOneEvent() {
        val id = fixture.quiz(category, difficulty, "VPC エンドポイントの目的は？", status = "draft")
        save(id, "VPC エンドポイントを使う目的は？", "draft")
        save(id, "VPC エンドポイントを使う目的は？", "published")
        save(id, "VPC エンドポイントを使う目的は？", "draft")
        mockMvc.post("/api/t/${tenant.slug}/admin/quizzes/import") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "quizzes" to listOf(
                        mapOf(
                            "category" to "AWS",
                            "difficulty" to "SAA",
                            "question" to "取り込んだ問題",
                            "explanation" to "解説",
                            "choices" to (1..4).map { mapOf("body" to "選択肢 $it", "isCorrect" to (it == 1)) },
                            "status" to "published",
                        ),
                    ),
                ),
            )
        }.andExpect { status { isCreated() } }

        val rows = outbox()
        assertThat(rows.map { it.eventType })
            .containsExactly("QuizCreated", "QuizUpdated", "QuizPublished", "QuizUnpublished", "QuizzesImported")

        val created = rows.first().payload
        assertThat(created["tenant"]["slug"].asString()).isEqualTo(tenant.slug)
        assertThat(created["tenant"]["name"].asString()).isEqualTo("ホテル")
        assertThat(created["quiz"]["id"].asString()).isEqualTo(id.toString())
        assertThat(created["quiz"]["status"].asString()).isEqualTo("DRAFT")
        assertThat(created["quiz"]["category"]["name"].asString()).isEqualTo("AWS")
        assertThat(created["quiz"]["question"].asString()).isEqualTo("VPC エンドポイントの目的は？")
        assertThat(rows.last().payload["imported"]["total"].asInt()).isEqualTo(1)

        // 送り直しても変わらない ID を、行と detail の両方に持つ
        val ids = TestPostgres.adminJdbcTemplate.queryForList(
            "SELECT id::text = payload->>'eventId' FROM quiz.outbox WHERE tenant_id = ?",
            Boolean::class.java,
            tenant.id,
        )
        assertThat(ids).containsOnly(true)
    }

    @Test
    @DisplayName("中身を変えずに保存しても、書かれない")
    fun unchangedSaveWritesNothing() {
        val id = fixture.quiz(category, difficulty, "問題文")

        save(id, "問題文", "published")

        assertThat(outbox().map { it.eventType }).containsExactly("QuizCreated")
    }

    @Test
    @DisplayName("クイズを保存したあとでトランザクションが失敗すると、クイズもイベントも残らない")
    fun rollbackRemovesBoth() {
        TenantContext.set(tenant.id)
        val choices = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }

        // ユースケースのトランザクションは、外側のものに加わる。外側が失敗すれば、まとめて取り消される
        assertThatThrownBy {
            tenantTransaction.executeWithoutResult {
                quizUseCase.create(category, difficulty, "問題文", "解説", choices, QuizStatus.PUBLISHED)
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM quiz.outbox", Int::class.java))
                    .isEqualTo(1)
                error("保存のあとで失敗する")
            }
        }.hasMessage("保存のあとで失敗する")

        assertThat(quizCount()).isZero()
        assertThat(outbox()).isEmpty()
    }

    /** 2 つのテナントに、クイズを 1 つずつ作る。どちらの Outbox にも行が 1 つずつできる */
    private fun quizzesInTwoTenants(): UUID {
        fixture.quiz(category, difficulty, "問題文")
        val other = TestTenant.create("インディア").withAdmin()
        val otherFixture = PlayFixture(mockMvc, objectMapper, other.slug)
        val otherCategory = otherFixture.category("AWS")
        otherFixture.quiz(otherCategory, otherFixture.difficulty(otherCategory, "SAA", 2), "問題文")
        return other.id
    }

    /** 拾い直し（DEV-97）が立てる印。`SET LOCAL` 相当で、トランザクションが終われば消える */
    private fun actAsRelay() {
        jdbcTemplate.queryForObject("SELECT set_config('app.outbox_relay', 'on', true)", String::class.java)
    }

    private fun visibleTenants(vararg tenantIds: UUID): List<UUID> = jdbcTemplate.queryForList(
        "SELECT DISTINCT tenant_id FROM quiz.outbox WHERE tenant_id = ANY (?)",
        UUID::class.java,
        tenantIds,
    ).filterNotNull()

    @Test
    @DisplayName("別のテナントの Outbox の行は見えない")
    fun otherTenantRowsAreInvisible() {
        val other = quizzesInTwoTenants()

        val visible = transactionTemplate.execute {
            tenantSession.apply(tenant.id)
            visibleTenants(tenant.id, other)
        }
        val withoutTenant = transactionTemplate.execute { visibleTenants(tenant.id, other) }

        assertThat(visible).containsExactly(tenant.id)
        assertThat(withoutTenant).isEmpty()
    }

    @Test
    @DisplayName("拾い直しの印を立てたときだけ、テナントをまたいで読み、送れた印を付けられる")
    fun relayReadsAcrossTenants() {
        val other = quizzesInTwoTenants()

        val visible = transactionTemplate.execute {
            actAsRelay()
            visibleTenants(tenant.id, other)
        }
        val marked = transactionTemplate.execute {
            actAsRelay()
            jdbcTemplate.update(
                "UPDATE quiz.outbox SET published_at = now() WHERE tenant_id = ANY (?)",
                arrayOf(tenant.id, other),
            )
        }
        // 印はトランザクションの終わりで消える。次のトランザクションには持ち越さない
        val afterwards = transactionTemplate.execute { visibleTenants(tenant.id, other) }

        assertThat(visible).containsExactlyInAnyOrder(tenant.id, other)
        assertThat(marked).isEqualTo(2)
        assertThat(afterwards).isEmpty()
    }

    @Test
    @DisplayName("拾い直しの印を立てても、イベントは書けない")
    fun relayCannotInsert() {
        // Spring は SQLState 42501（権限不足）を BadSqlGrammarException に分類する（TenantIsolationTest を参照）
        assertThatThrownBy {
            transactionTemplate.execute {
                actAsRelay()
                jdbcTemplate.update(
                    """
                    INSERT INTO quiz.outbox (id, tenant_id, event_type, payload, occurred_at)
                    VALUES (?, ?, 'QuizCreated', '{}', now())
                    """.trimIndent(),
                    UUID.randomUUID(),
                    tenant.id,
                )
            }
        }.isInstanceOf(BadSqlGrammarException::class.java)
            .rootCause()
            .hasMessageContaining("row-level security")
    }
}
