package com.quizapp.quiz.infrastructure.newsquiz

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.quiz.usecase.QuizImportUseCase
import com.quizapp.support.MutableClock
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestTenant
import com.quizapp.support.fake.InMemoryNewsQuizInbox
import com.quizapp.tenant.TenantTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * AI が技術ニュースから作った下書きを、受け箱から取り込む（ADR-0031、DEV-161）。
 *
 * 取り込みのユースケースと DB は本物を通し、受け箱（S3）と時計だけを差し替える
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewsQuizImportApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SOURCE = "https://aws.amazon.com/about-aws/whats-new/2026/10/example/"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var importer: QuizImportUseCase

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var tenantTransaction: TenantTransaction

    private val sql = TestPostgres.adminJdbcTemplate
    private val clock = MutableClock()
    private val activity = DatabaseActivity(clock)
    private val inbox = InMemoryNewsQuizInbox()

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("ニュース").withAdmin()
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        fixture.difficulty(fixture.category("AWS の最新情報"), "ニュース", 1)
    }

    private fun job(maxPendingDrafts: Int = 30, tenantSlug: String = tenant.slug) = NewsQuizImport(
        inbox,
        NewsQuizDrafts(jdbcTemplate, tenantTransaction),
        importer,
        activity,
        NewsQuizProperties(enabled = true, tenantSlug = tenantSlug, maxPendingDrafts = maxPendingDrafts),
        clock,
    )

    private fun draft(
        question: String,
        source: String = SOURCE,
        correct: List<Boolean> = listOf(true, false, false, false),
        version: Int = 1,
    ): String {
        val choices = correct.mapIndexed { i, isCorrect -> mapOf("body" to "選択肢 ${i + 1}", "isCorrect" to isCorrect) }
        return objectMapper.writeValueAsString(
            mapOf(
                "version" to version,
                "source" to mapOf("url" to source, "title" to "発表の見出し"),
                "quiz" to
                    mapOf("question" to question, "choices" to choices, "explanation" to "解説\n\n出典: [見出し]($source)"),
            ),
        )
    }

    /** このテナントのクイズ（問題文、状態、出典の URL） */
    private fun quizzes(): List<Triple<String, String, String?>> = sql.query(
        "SELECT question, status, source_url FROM quiz.quizzes WHERE tenant_id = ? ORDER BY question",
        { rs, _ -> Triple(rs.getString("question"), rs.getString("status"), rs.getString("source_url")) },
        tenant.id,
    )

    @Test
    @DisplayName("使われたあとに、受け箱の下書きを下書きのクイズとして入れ、出典の URL を持たせ、受け箱から消す")
    fun importsAsDrafts() {
        inbox.put("inbox/1.json", draft("問題 1"))
        inbox.put("inbox/2.json", draft("問題 2"))
        activity.record()

        job().tick()

        assertThat(quizzes()).containsExactly(Triple("問題 1", "draft", SOURCE), Triple("問題 2", "draft", SOURCE))
        assertThat(inbox.keys()).isEmpty()
    }

    @Test
    @DisplayName("使われていなければ見ない。一度見たら、1 時間は見ない")
    fun onlyWhileUsedAndHourly() {
        val job = job()
        inbox.put("inbox/1.json", draft("問題 1"))

        job.tick()
        assertThat(quizzes()).isEmpty()

        activity.record()
        job.tick()
        inbox.put("inbox/2.json", draft("問題 2"))
        clock.advance(Duration.ofMinutes(59))
        activity.record()
        job.tick()
        assertThat(quizzes().map { it.first }).containsExactly("問題 1")

        clock.advance(Duration.ofMinutes(1))
        job.tick()
        assertThat(quizzes().map { it.first }).containsExactly("問題 1", "問題 2")
    }

    @Test
    @DisplayName("確かめていない AI の下書きが上限に達していれば、取り込まずに受け箱に残す。公開すれば、また取り込む")
    fun stopsWhileDraftsPileUp() {
        val job = job(maxPendingDrafts = 1)
        inbox.put("inbox/1.json", draft("問題 1"))
        inbox.put("inbox/2.json", draft("問題 2"))
        activity.record()

        job.tick()
        assertThat(quizzes().map { it.first }).containsExactly("問題 1")
        assertThat(inbox.keys()).containsExactly("inbox/2.json")

        sql.update("UPDATE quiz.quizzes SET status = 'published' WHERE tenant_id = ?", tenant.id)
        clock.advance(Duration.ofHours(1))
        activity.record()
        job.tick()
        assertThat(quizzes().map { it.first }).containsExactly("問題 1", "問題 2")
    }

    @Test
    @DisplayName("読めないもの、知らない版、公開できる形でないもの、出典が情報源の外のもの、同じ問題文のものは、入れずに捨てる")
    fun discardsInvalid() {
        inbox.put("inbox/0.json", draft("問題 1"))
        inbox.put("inbox/1.json", "{ 読めない")
        inbox.put("inbox/2.json", draft("知らない版", version = 2))
        inbox.put("inbox/3.json", draft("正解が 2 つ", correct = listOf(true, true, false, false)))
        inbox.put("inbox/4.json", draft("選択肢が 3 つ", correct = listOf(true, false, false)))
        inbox.put("inbox/5.json", draft("外の出典", source = "https://example.com/news"))
        inbox.put("inbox/6.json", draft("問題 1"))
        activity.record()

        job().tick()

        assertThat(quizzes().map { it.first }).containsExactly("問題 1")
        assertThat(inbox.keys()).isEmpty()
    }

    @Test
    @DisplayName("入れる先のテナントがなければ、何もせず受け箱に残す")
    fun leavesInboxWithoutTenant() {
        inbox.put("inbox/1.json", draft("問題 1"))
        activity.record()

        job(tenantSlug = "no-such-tenant").tick()

        assertThat(inbox.keys()).containsExactly("inbox/1.json")
    }
}
