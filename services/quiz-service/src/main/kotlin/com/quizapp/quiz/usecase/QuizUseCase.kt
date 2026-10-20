package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.AnswerTally
import com.quizapp.quiz.domain.AnsweredQuizzes
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.LimitedResource
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizChange
import com.quizapp.quiz.domain.QuizEventOutbox
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.domain.TenantCapacity
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

// 8 つ目は、テナントの上限（ADR-0028）。作成の前に確かめる番人で、ほかの型に混ぜると、確かめていることが見えなくなる。
// 削除も各ユースケースが持つ形で揃えているため、ずらして減らすことはしない。
// 9 つ目は、一覧に載せる回答の集計（DEV-158）。answer モジュールの記録で、クイズのリポジトリには混ぜられない
@Suppress("LongParameterList")
@Service
class QuizUseCase(
    private val quizRepository: QuizRepository,
    private val categoryRepository: CategoryRepository,
    private val difficultyRepository: DifficultyRepository,
    private val deletion: DeletionRepository,
    private val figureRepository: FigureRepository,
    private val outbox: QuizEventOutbox,
    private val capacity: TenantCapacity,
    private val answeredQuizzes: AnsweredQuizzes,
    private val tenantTransaction: TenantTransaction,
) {
    /** 回答の集計を添えて返す。集計は、件数によらず 1 本の SQL で読む */
    fun search(categoryId: UUID?, difficultyId: UUID?, status: QuizStatus?, aiGenerated: Boolean?): List<QuizListItem> =
        tenantTransaction.execute {
            val quizzes = quizRepository.search(categoryId, difficultyId, status, aiGenerated)
            val tallies = answeredQuizzes.tally(quizzes.mapNotNull { it.id })
            quizzes.map { QuizListItem(it, tallies[it.id] ?: AnswerTally.NONE) }
        }

    fun get(id: UUID): Quiz = tenantTransaction.execute {
        quizRepository.findById(id) ?: throw QuizNotFoundException(id)
    }

    /** 選択肢ごとの回答の数を添えて返す（DEV-170） */
    fun getDetail(id: UUID): QuizDetail = tenantTransaction.execute {
        val quiz = quizRepository.findById(id) ?: throw QuizNotFoundException(id)
        QuizDetail(quiz, answeredQuizzes.countChoices(id))
    }

    fun create(
        categoryId: UUID,
        difficultyId: UUID,
        question: String,
        explanation: String,
        choices: List<Choice>,
        status: QuizStatus,
    ): Quiz = tenantTransaction.execute {
        capacity.requireRoomFor(LimitedResource.QUIZZES)
        verifyCategoryAndDifficulty(categoryId, difficultyId)
        val quiz = Quiz(
            categoryId = categoryId,
            difficultyId = difficultyId,
            question = question,
            explanation = explanation,
            choices = choices,
            status = status,
        )
        verifyFigures(quiz)
        quizRepository.save(quiz).also { outbox.quizChanged(QuizChange.CREATED, it) }
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
        val before = quizRepository.findById(id) ?: throw QuizNotFoundException(id)
        verifyCategoryAndDifficulty(categoryId, difficultyId)
        val quiz = Quiz(
            id = id,
            categoryId = categoryId,
            difficultyId = difficultyId,
            question = question,
            explanation = explanation,
            choices = choices,
            status = status,
            // 出典は、AI が作ったときに決まる。直しても公開しても、元にした発表は変わらない（ADR-0031）
            sourceUrl = before.sourceUrl,
        )
        verifyFigures(quiz)
        quizRepository.save(quiz).also { saved ->
            QuizChange.between(before, quiz)?.let { outbox.quizChanged(it, saved) }
        }
    }

    /** 削除と復活は、イベントを送らない（ADR-0022）。受け手がいない */
    fun delete(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!deletion.deleteQuiz(id)) throw QuizNotFoundException(id)
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

    /**
     * 解説が指す図が、このテナントにあるかを確かめる（ADR-0020）。
     * 別テナントの図は行レベルセキュリティで見えないため、無い図として扱われる
     */
    private fun verifyFigures(quiz: Quiz) {
        val ids = quiz.figureIds()
        require(figureRepository.findExisting(ids).size == ids.size) { Quiz.FIGURE_NOT_FOUND }
    }
}

class QuizNotFoundException(val id: UUID) : RuntimeException("クイズが見つかりません: $id")

data class QuizListItem(val quiz: Quiz, val answers: AnswerTally)

/** [choiceCounts] は選択肢の ID ごとの回答の数。いまはない選択肢への回答も含む */
data class QuizDetail(val quiz: Quiz, val choiceCounts: Map<UUID, Int>)
