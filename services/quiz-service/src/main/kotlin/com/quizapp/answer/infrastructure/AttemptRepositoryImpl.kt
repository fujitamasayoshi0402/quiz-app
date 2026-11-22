package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.Answer
import com.quizapp.answer.domain.Attempt
import com.quizapp.answer.domain.AttemptRepository
import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.answer.domain.ConcurrentAttemptStartException
import com.quizapp.answer.domain.DuplicateAnswerException
import com.quizapp.quiz.domain.DeliveryScope
import com.quizapp.tenant.TenantContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AttemptRepositoryImpl(
    private val attempts: AttemptJdbcRepository,
    private val answers: AnswerJdbcRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : AttemptRepository {

    override fun create(attempt: Attempt): Attempt {
        val tenantId = TenantContext.require()
        val entity = AttemptEntity(
            tenantId = tenantId,
            userId = attempt.userId,
            categoryId = attempt.categoryId,
            difficultyId = attempt.difficultyId,
            level = attempt.level,
            scope = attempt.scope.name.lowercase(),
            status = attempt.status.name.lowercase(),
            quizzes = attempt.quizIds.map { AttemptQuizEntity(tenantId = tenantId, quizId = it) },
        )
        return try {
            attempts.save(entity).toDomain()
        } catch (e: DuplicateKeyException) {
            // attempts_single_in_progress_idx。先に SELECT で確かめていても、同時に始めると抜けるため、DB の制約を正とする
            throw ConcurrentAttemptStartException(e)
        }
    }

    override fun findById(id: UUID): Attempt? = attempts.findById(id).orElse(null)?.toDomain()

    override fun findInProgress(userId: UUID): Attempt? = attempts.findInProgress(userId)?.toDomain()

    override fun finish(id: UUID, status: AttemptStatus): Boolean = attempts.finish(id, status.name.lowercase()) > 0

    override fun record(answer: Answer): Answer {
        val tenantId = TenantContext.require()
        val entity = AnswerEntity(
            tenantId = tenantId,
            attemptId = answer.attemptId,
            userId = answer.userId,
            quizId = answer.quizId,
            choiceId = answer.choiceId,
            isCorrect = answer.isCorrect,
        )
        val saved = try {
            answers.save(entity)
        } catch (e: DuplicateKeyException) {
            // (attempt_id, quiz_id) のユニークインデックス。
            // 先に SELECT で確認する形にすると同時実行で抜けるため、DB の制約を正とする
            throw DuplicateAnswerException(answer.quizId, e)
        }
        val answerId = requireNotNull(saved.id)
        val choices = answer.choiceIds.ifEmpty { listOf(answer.choiceId) }.distinct()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO answer.answer_choices (tenant_id, answer_id, choice_id, sort_order)
            VALUES (:tenantId, :answerId, :choiceId, :sortOrder)
            """.trimIndent(),
            choices.mapIndexed { index, choiceId ->
                MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("answerId", answerId)
                    .addValue("choiceId", choiceId)
                    .addValue("sortOrder", index)
            }.toTypedArray(),
        )
        return saved.toDomain(choices)
    }

    override fun findAnswers(attemptId: UUID): List<Answer> {
        val entities = answers.findByAttemptId(attemptId)
        if (entities.isEmpty()) return emptyList()
        val answerIds = entities.mapNotNull { it.id }
        val choiceIdsByAnswer = jdbcTemplate.query(
            """
            SELECT answer_id, choice_id FROM answer.answer_choices
            WHERE answer_id IN (:answerIds)
            ORDER BY answer_id, sort_order
            """.trimIndent(),
            mapOf("answerIds" to answerIds),
        ) { rs, _ ->
            rs.getObject("answer_id", UUID::class.java) to rs.getObject("choice_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second })

        return entities.map { entity ->
            val recorded = choiceIdsByAnswer[entity.id].orEmpty().ifEmpty { listOf(entity.choiceId) }
            entity.toDomain(recorded)
        }
    }

    private fun AttemptEntity.toDomain() = Attempt(
        id = id,
        userId = userId,
        categoryId = categoryId,
        difficultyId = difficultyId,
        level = level,
        scope = DeliveryScope.from(scope),
        quizIds = quizzes.map { it.quizId },
        status = AttemptStatus.from(status),
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    private fun AnswerEntity.toDomain(selectedChoiceIds: List<UUID> = listOf(choiceId)) = Answer(
        id = id,
        attemptId = attemptId,
        userId = userId,
        quizId = quizId,
        choiceId = choiceId,
        choiceIds = selectedChoiceIds,
        isCorrect = isCorrect,
        answeredAt = answeredAt,
    )
}
