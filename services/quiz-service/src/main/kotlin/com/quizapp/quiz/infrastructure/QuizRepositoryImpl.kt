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

    override fun search(
        categoryId: UUID?,
        difficultyId: UUID?,
        status: QuizStatus?,
        aiGenerated: Boolean?,
    ): List<Quiz> = listJdbc.search(categoryId, difficultyId, status?.name?.lowercase(), aiGenerated)

    override fun findById(id: UUID): Quiz? = jdbcRepository.findActiveById(id)?.toDomain()

    override fun findPublishedCandidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz> =
        listJdbc.findPublishedCandidates(categoryId, difficultyId, level)

    override fun findPublishedByIds(ids: List<UUID>): List<Quiz> = listJdbc.findPublishedByIds(ids)

    override fun save(quiz: Quiz): Quiz {
        val tenantId = TenantContext.require()
        val existing = quiz.id?.let {
            jdbcRepository.findActiveById(it) ?: throw IllegalArgumentException("クイズが見つかりません: $it")
        }
        // 選択肢は親と同じテナントに属する。複合外部キーの構成要素なので、
        // 値がずれると挿入時に外部キー違反になる
        val choices = keptIds(quiz.choices, existing).map { (choice, id) ->
            ChoiceEntity(id = id, tenantId = tenantId, body = choice.body, isCorrect = choice.isCorrect)
        }

        val entity = if (existing == null) {
            QuizEntity(
                tenantId = tenantId,
                categoryId = quiz.categoryId,
                difficultyId = quiz.difficultyId,
                question = quiz.question,
                explanation = quiz.explanation,
                status = quiz.status.name.lowercase(),
                sourceUrl = quiz.sourceUrl,
                choices = choices,
            )
        } else {
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

    /**
     * 保存しても、選択肢の ID を保つ（DEV-170）。回答は選んだ選択肢の ID で残るため、作り直すと誰が何を選んだかが分からなくなる。
     *
     * 引き継ぐのは、**このクイズにいまある選択肢の ID だけ。** ほかのクイズの ID を渡されても使わない。
     * 同じ ID が 2 度あれば、2 つ目からは新しい ID にする。どちらも、そのまま入れると主キーがぶつかる。
     * Spring Data JDBC は保存のたびに子を消して入れ直すため、ID を渡せばその ID で入る。
     */
    private fun keptIds(choices: List<Choice>, existing: QuizEntity?): List<Pair<Choice, UUID?>> {
        val available = existing?.choices?.mapNotNull { it.id }.orEmpty().toMutableSet()
        return choices.map { choice -> choice to choice.id?.takeIf(available::remove) }
    }

    private fun QuizEntity.toDomain() = Quiz(
        id = id,
        categoryId = categoryId,
        difficultyId = difficultyId,
        question = question,
        explanation = explanation,
        choices = choices.map { Choice(id = it.id, body = it.body, isCorrect = it.isCorrect) },
        status = QuizStatus.from(status),
        sourceUrl = sourceUrl,
    )
}
