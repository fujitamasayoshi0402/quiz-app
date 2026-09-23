package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QuizUseCase(
    private val quizRepository: QuizRepository,
    private val categoryRepository: CategoryRepository,
    private val difficultyRepository: DifficultyRepository,
    private val tenantTransaction: TenantTransaction,
) {
    fun search(categoryId: UUID?, difficultyId: UUID?, status: QuizStatus?): List<Quiz> =
        tenantTransaction.execute { quizRepository.search(categoryId, difficultyId, status) }

    fun get(id: UUID): Quiz = tenantTransaction.execute {
        quizRepository.findById(id) ?: throw QuizNotFoundException(id)
    }

    fun create(
        categoryId: UUID,
        difficultyId: UUID,
        question: String,
        explanation: String,
        choices: List<Choice>,
        status: QuizStatus,
    ): Quiz = tenantTransaction.execute {
        verifyCategoryAndDifficulty(categoryId, difficultyId)
        quizRepository.save(
            Quiz(
                categoryId = categoryId,
                difficultyId = difficultyId,
                question = question,
                explanation = explanation,
                choices = choices,
                status = status,
            ),
        )
    }

    fun update(
        id: UUID,
        categoryId: UUID,
        difficultyId: UUID,
        question: String,
        explanation: String,
        choices: List<Choice>,
        status: QuizStatus,
    ): Quiz = tenantTransaction.execute {
        quizRepository.findById(id) ?: throw QuizNotFoundException(id)
        verifyCategoryAndDifficulty(categoryId, difficultyId)
        quizRepository.save(
            Quiz(
                id = id,
                categoryId = categoryId,
                difficultyId = difficultyId,
                question = question,
                explanation = explanation,
                choices = choices,
                status = status,
            ),
        )
    }

    fun delete(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!quizRepository.softDelete(id)) throw QuizNotFoundException(id)
    }

    /**
     * カテゴリと難易度の組み合わせを確認する。
     *
     * この整合性は DB の複合外部キーでも守られる。ここで検証するのは、
     * **制約違反をそのまま返すと利用者に何が問題か伝わらない**ため。
     * DB は最後の砦であって、入力エラーの説明役ではない。
     */
    private fun verifyCategoryAndDifficulty(categoryId: UUID, difficultyId: UUID) {
        categoryRepository.findById(categoryId) ?: throw CategoryNotFoundException(categoryId)
        val difficulty = difficultyRepository.findById(difficultyId)
            ?: throw DifficultyNotFoundException(difficultyId)
        require(difficulty.categoryId == categoryId) { "指定された難易度はこのカテゴリのものではありません" }
    }
}

class QuizNotFoundException(val id: UUID) : RuntimeException("クイズが見つかりません: $id")
