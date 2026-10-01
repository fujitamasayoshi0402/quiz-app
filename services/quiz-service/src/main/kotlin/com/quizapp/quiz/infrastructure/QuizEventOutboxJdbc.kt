package com.quizapp.quiz.infrastructure

import com.quizapp.events.CategoryRef
import com.quizapp.events.ImportedCount
import com.quizapp.events.QuizCreated
import com.quizapp.events.QuizEvent
import com.quizapp.events.QuizEventStatus
import com.quizapp.events.QuizPublished
import com.quizapp.events.QuizRef
import com.quizapp.events.QuizUnpublished
import com.quizapp.events.QuizUpdated
import com.quizapp.events.QuizzesImported
import com.quizapp.events.TenantRef
import com.quizapp.events.detailType
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizChange
import com.quizapp.quiz.domain.QuizEventOutbox
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.infrastructure.outbox.OutboxEntry
import com.quizapp.quiz.infrastructure.outbox.OutboxPublisher
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * クイズのイベントを `quiz.outbox` に書く（ADR-0022）。
 *
 * 呼び出し側のトランザクションに乗る。テナントは [TenantContext] から取り、行レベルセキュリティの下で書く。
 * 行の `payload` が EventBridge の `detail`、`event_type` が `detail-type` になる。
 * 書いたイベントは、コミットの直後に [OutboxPublisher] が送る（DEV-97）
 */
@Component
class QuizEventOutboxJdbc(
    private val jdbcTemplate: JdbcTemplate,
    private val categoryRepository: CategoryRepository,
    private val publisher: OutboxPublisher,
) : QuizEventOutbox {

    override fun quizChanged(change: QuizChange, quiz: Quiz) {
        val eventId = UUID.randomUUID()
        val occurredAt = now()
        val tenant = currentTenant()
        val ref = quizRef(quiz)
        append(
            when (change) {
                QuizChange.CREATED -> QuizCreated(eventId, occurredAt, tenant, ref)
                QuizChange.UPDATED -> QuizUpdated(eventId, occurredAt, tenant, ref)
                QuizChange.PUBLISHED -> QuizPublished(eventId, occurredAt, tenant, ref)
                QuizChange.UNPUBLISHED -> QuizUnpublished(eventId, occurredAt, tenant, ref)
            },
        )
    }

    override fun quizzesImported(total: Int, published: Int) = append(
        QuizzesImported(UUID.randomUUID(), now(), currentTenant(), ImportedCount(total = total, published = published)),
    )

    private fun append(event: QuizEvent) {
        val payload = QuizEventJson.write(event)
        jdbcTemplate.update(
            """
            INSERT INTO quiz.outbox (id, tenant_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            event.eventId,
            event.tenant.id,
            event.detailType,
            payload,
            event.occurredAt.atOffset(ZoneOffset.UTC),
        )
        publisher.publishAfterCommit(OutboxEntry(event.eventId, event.tenant.id, event.detailType, payload))
    }

    /** `core.tenants` は行レベルセキュリティの対象外。テナントの名前と slug は、通知の文面とリンクに使う */
    private fun currentTenant(): TenantRef {
        val id = TenantContext.require()
        return jdbcTemplate.queryForObject(
            "SELECT slug, name FROM core.tenants WHERE id = ?",
            { rs, _ -> TenantRef(id = id, slug = rs.getString("slug"), name = rs.getString("name")) },
            id,
        ) ?: error("テナントが見つかりません: $id")
    }

    private fun quizRef(quiz: Quiz): QuizRef {
        val category = categoryRepository.findById(quiz.categoryId)
            ?: error("クイズのカテゴリが見つかりません: ${quiz.categoryId}")
        return QuizRef(
            id = requireNotNull(quiz.id) { "保存したクイズには ID があるはずです" },
            status = when (quiz.status) {
                QuizStatus.DRAFT -> QuizEventStatus.DRAFT
                QuizStatus.PUBLISHED -> QuizEventStatus.PUBLISHED
            },
            category = CategoryRef(id = quiz.categoryId, name = category.name),
            question = excerpt(quiz.question),
        )
    }

    companion object {
        /**
         * 問題文の冒頭。文字（コードポイント）で数えて切る。
         * `String.take` は UTF-16 の単位で数えるため、絵文字などの途中で切れて壊れた文字が残りうる
         */
        fun excerpt(question: String): String {
            val limit = QuizRef.QUESTION_EXCERPT_LENGTH
            if (question.codePointCount(0, question.length) <= limit) return question
            return question.substring(0, question.offsetByCodePoints(0, limit))
        }

        /** DB の timestamptz はマイクロ秒まで。見本と揃えやすいよう、ミリ秒で切る */
        private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    }
}
