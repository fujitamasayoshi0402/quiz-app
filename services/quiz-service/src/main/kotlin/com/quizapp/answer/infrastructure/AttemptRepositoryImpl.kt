package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.Answer
import com.quizapp.answer.domain.Attempt
import com.quizapp.answer.domain.AttemptRepository
import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.answer.domain.DuplicateAnswerException
import com.quizapp.quiz.domain.DeliveryScope
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AttemptRepositoryImpl(private val attempts: AttemptJdbcRepository, private val answers: AnswerJdbcRepository) :
    AttemptRepository {

    override fun create(attempt: Attempt): Attempt {
        val entity = AttemptEntity(
            userId = attempt.userId,
            categoryId = attempt.categoryId,
            difficultyId = attempt.difficultyId,
            level = attempt.level,
            scope = attempt.scope.name.lowercase(),
            status = attempt.status.name.lowercase(),
            quizzes = attempt.quizIds.map { AttemptQuizEntity(quizId = it) },
        )
        return attempts.save(entity).toDomain()
    }

    override fun findById(id: UUID): Attempt? = attempts.findById(id).orElse(null)?.toDomain()

    override fun findInProgress(userId: UUID): Attempt? = attempts.findInProgress(userId)?.toDomain()

    override fun finish(id: UUID, status: AttemptStatus): Boolean = attempts.finish(id, status.name.lowercase()) > 0

    override fun record(answer: Answer): Answer {
        val entity = AnswerEntity(
            attemptId = answer.attemptId,
            userId = answer.userId,
            quizId = answer.quizId,
            choiceId = answer.choiceId,
            isCorrect = answer.isCorrect,
        )
        return try {
            answers.save(entity).toDomain()
        } catch (e: DuplicateKeyException) {
            // (attempt_id, quiz_id) のユニークインデックス。
            // 先に SELECT で確認する形にすると同時実行で抜けるため、DB の制約を正とする
            throw DuplicateAnswerException(answer.quizId, e)
        }
    }

    override fun findAnswers(attemptId: UUID): List<Answer> = answers.findByAttemptId(attemptId).map { it.toDomain() }

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

    private fun AnswerEntity.toDomain() = Answer(
        id = id,
        attemptId = attemptId,
        userId = userId,
        quizId = quizId,
        choiceId = choiceId,
        isCorrect = isCorrect,
        answeredAt = answeredAt,
    )
}
