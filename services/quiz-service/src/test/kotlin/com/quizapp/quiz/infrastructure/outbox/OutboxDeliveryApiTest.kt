package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.quiz.usecase.QuizUseCase
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestTenant
import com.quizapp.support.eventually
import com.quizapp.support.fake.InMemoryEventBus
import com.quizapp.tenant.TenantContext
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Outbox に書いたイベントを送る（ADR-0022）。DB でしか確かめられないことを見る。
 * - コミットしたら送り、送れた印を付ける。ロールバックしたら送らない
 * - バスに送れなくても、クイズの操作は成功する
 * - 拾い直しは、テナントをまたいで送れていない行を送り、ほかのタスクがロックした行は飛ばす
 *
 * 拾い直しを動かす条件（DB を使った直後だけ）は `OutboxRelayTest` が見る。ここでは SQL の部分（[OutboxRows.relay]）を直接呼ぶ。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxDeliveryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var bus: InMemoryEventBus

    @Autowired private lateinit var rows: OutboxRows

    @Autowired private lateinit var tenantTransaction: TenantTransaction

    @Autowired private lateinit var quizUseCase: QuizUseCase

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        bus.down = false
        bus.failing.clear()
        tenant = TestTenant.create("ジュリエット").withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    @AfterEach
    fun tearDown() {
        bus.down = false
        TenantContext.clear()
    }

    private fun eventIds(tenantId: UUID = tenant.id): List<UUID> = TestPostgres.adminJdbcTemplate.queryForList(
        "SELECT id FROM quiz.outbox WHERE tenant_id = ? ORDER BY occurred_at",
        UUID::class.java,
        tenantId,
    ).filterNotNull()

    private fun publishedAt(id: UUID): OffsetDateTime? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT published_at FROM quiz.outbox WHERE id = ?",
        OffsetDateTime::class.java,
        id,
    )

    /** 拾い直しの対象になるよう、行のできた時刻を昔にする */
    private fun age(id: UUID) {
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE quiz.outbox SET occurred_at = occurred_at - interval '10 minutes' WHERE id = ?",
            id,
        )
    }

    /** このテスト用に、ほかのテストが残した行を拾わないよう、時刻で範囲を絞って拾い直す */
    private fun relayNow(): RelayResult = rows.relay(
        olderThan = Instant.now().minusSeconds(60),
        limit = 1000,
        publishedBefore = Instant.EPOCH,
        send = { entries -> bus.publish(entries.filter { it.tenantId in relayTargets }) },
    )

    private val relayTargets = mutableSetOf<UUID>()

    @Test
    @DisplayName("コミットしたら送り、送れた印を付ける")
    fun publishesAfterCommit() {
        fixture.quiz(category, difficulty, "問題文")
        val id = eventIds().single()

        eventually {
            assertThat(bus.publishedIds()).contains(id)
            assertThat(publishedAt(id)).isNotNull()
        }
        val sent = bus.published.single { it.id == id }
        assertThat(sent.eventType).isEqualTo("QuizCreated")
        assertThat(objectMapper.readTree(sent.payload)["eventId"].asString()).isEqualTo(id.toString())
    }

    @Test
    @DisplayName("ロールバックしたら送らない")
    fun doesNotPublishOnRollback() {
        TenantContext.set(tenant.id)
        val choices = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }
        val before = bus.published.size

        assertThatThrownBy {
            tenantTransaction.executeWithoutResult {
                quizUseCase.create(category, difficulty, "問題文", "解説", choices, QuizStatus.PUBLISHED)
                error("保存のあとで失敗する")
            }
        }.hasMessage("保存のあとで失敗する")

        // 送るのは別のスレッド。送らないことを確かめるため、少し待ってから見る
        Thread.sleep(300)
        assertThat(bus.published.size).isEqualTo(before)
        assertThat(eventIds()).isEmpty()
    }

    @Test
    @DisplayName("バスに送れなくても、クイズの操作は成功し、行は送れていないまま残る")
    fun operationSucceedsWhenBusIsDown() {
        bus.down = true

        fixture.quiz(category, difficulty, "問題文")

        val id = eventIds().single()
        Thread.sleep(300)
        assertThat(publishedAt(id)).isNull()
    }

    @Test
    @DisplayName("拾い直しは、テナントをまたいで送れていない行を送り、印を付ける")
    fun relaySendsAcrossTenants() {
        bus.down = true
        fixture.quiz(category, difficulty, "問題文")
        val other = TestTenant.create("キロ").withAdmin()
        val otherFixture = PlayFixture(mockMvc, objectMapper, other.slug)
        val otherCategory = otherFixture.category("AWS")
        otherFixture.quiz(otherCategory, otherFixture.difficulty(otherCategory, "SAA", 2), "問題文")
        val ids = eventIds() + eventIds(other.id)
        Thread.sleep(300)
        ids.forEach(::age)
        relayTargets += listOf(tenant.id, other.id)
        bus.down = false

        relayNow()

        assertThat(bus.publishedIds()).containsAll(ids)
        ids.forEach { assertThat(publishedAt(it)).isNotNull() }
    }

    @Test
    @DisplayName("1 分より新しい行は拾わない")
    fun relaySkipsFreshRows() {
        bus.down = true
        fixture.quiz(category, difficulty, "問題文")
        val id = eventIds().single()
        Thread.sleep(300)
        relayTargets += tenant.id
        bus.down = false

        relayNow()

        assertThat(bus.publishedIds()).doesNotContain(id)
        assertThat(publishedAt(id)).isNull()
    }

    @Test
    @DisplayName("ほかのタスクがロックしている行は飛ばす")
    fun relaySkipsLockedRows() {
        bus.down = true
        fixture.quiz(category, difficulty, "1 問目")
        fixture.quiz(category, difficulty, "2 問目")
        val (locked, free) = eventIds()
        Thread.sleep(300)
        listOf(locked, free).forEach(::age)
        relayTargets += tenant.id
        bus.down = false

        // 別のタスクの拾い直しが、行をロックしている間
        TestPostgres.container.createConnection("").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM quiz.outbox WHERE id = ? FOR UPDATE").use {
                it.setObject(1, locked)
                it.executeQuery().close()
            }

            relayNow()

            connection.rollback()
        }

        assertThat(bus.publishedIds()).contains(free).doesNotContain(locked)
    }

    @Test
    @DisplayName("送ってから期限を過ぎた行を消す。送れていない最も古い行の時刻を返す")
    fun relayDeletesExpiredRowsAndReportsOldest() {
        bus.down = true
        fixture.quiz(category, difficulty, "1 問目")
        fixture.quiz(category, difficulty, "2 問目")
        val (expired, stuck) = eventIds()
        Thread.sleep(300)
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE quiz.outbox SET published_at = ? WHERE id = ?",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(8),
            expired,
        )

        val result = rows.relay(
            olderThan = Instant.EPOCH,
            limit = 1,
            publishedBefore = Instant.now().minus(java.time.Duration.ofDays(7)),
            send = { emptySet() },
        )

        assertThat(eventIds()).containsExactly(stuck)
        assertThat(result.deleted).isGreaterThanOrEqualTo(1)
        assertThat(result.oldestUnpublished).isNotNull()
    }
}
