package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

/** 1 件は集約のまま Spring Data JDBC で、何件もまとめて読むものは [QuizListJdbc] で読む */
@Component
class QuizRepositoryImpl(private val jdbcRepository: QuizJdbcRepository, private val listJdbc: QuizListJdbc) :
    QuizRepository {

    override fun search(categoryId: UUID?, difficultyId: UUID?, status: QuizStatus?): List<Quiz> =
        listJdbc.search(categoryId, difficultyId, status?.name?.lowercase())

    override fun findById(id: UUID): Quiz? = jdbcRepository.findActiveById(id)?.toDomain()

    override fun findPublishedCandidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz> =
        listJdbc.findPublishedCandidates(categoryId, difficultyId, level)

    override fun findPublishedByIds(ids: List<UUID>): List<Quiz> = listJdbc.findPublishedByIds(ids)

    override fun save(quiz: Quiz): Quiz {
        val tenantId = TenantContext.require()
        // 選択肢は親と同じテナントに属する。複合外部キーの構成要素なので、
        // 値がずれると挿入時に外部キー違反になる
        val choices = quiz.choices.map {
            ChoiceEntity(tenantId = tenantId, body = it.body, isCorrect = it.isCorrect)
        }

        val entity = if (quiz.id == null) {
            QuizEntity(
                tenantId = tenantId,
                categoryId = quiz.categoryId,
                difficultyId = quiz.difficultyId,
                question = quiz.question,
                explanation = quiz.explanation,
                status = quiz.status.name.lowercase(),
                choices = choices,
            )
        } else {
            val existing = jdbcRepository.findActiveById(quiz.id)
                ?: throw IllegalArgumentException("クイズが見つかりません: ${quiz.id}")
            existing.copy(
                categoryId = quiz.categoryId,
                difficultyId = quiz.difficultyId,
                question = quiz.question,
                explanation = quiz.explanation,
                status = quiz.status.name.lowercase(),
                choices = choices,
            )
        }
        return jdbcRepository.save(entity).toDomain()
    }

    private fun QuizEntity.toDomain() = Quiz(
        id = id,
        categoryId = categoryId,
        difficultyId = difficultyId,
        question = question,
        explanation = explanation,
        choices = choices.map { Choice(id = it.id, body = it.body, isCorrect = it.isCorrect) },
        status = QuizStatus.from(status),
    )
}
