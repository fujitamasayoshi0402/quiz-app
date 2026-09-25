package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/** 取り込む 1 行。カテゴリと難易度は名前で指す。検証の前なので、中身は不正でありうる */
data class QuizImportRow(
    val category: String,
    val difficulty: String,
    val question: String,
    val explanation: String,
    val choices: List<ChoiceInput>,
    val status: String,
) {
    data class ChoiceInput(val body: String, val isCorrect: Boolean)
}

/**
 * 取り込めない行。[index] はリクエストの中の位置（0 始まり）。
 *
 * **理由に、送られてきた値を含めない。** 名前を取り違えて別テナントのカテゴリ名を送っても、
 * それを応答で繰り返さない。どの値だったかは、送った側がファイルと位置から分かる。
 */
data class QuizImportRowError(val index: Int, val messages: List<String>)

class QuizImportRejectedException(val rows: List<QuizImportRowError>) : RuntimeException("取り込めない行があります")

/**
 * クイズをまとめて取り込む。
 *
 * **全か無か。** すべての行を検証し、1 行でも不正なら 1 件も保存せず、不正な行をまとめて返す。
 * 途中まで入った状態を残すと、直したファイルを取り込み直したときに重複する。
 *
 * カテゴリと難易度は名前で指す。無いものは作らない。名前の打ち間違いで意図しないカテゴリができないように、
 * 先に管理画面で作っておく。名前はテナントの中で一意なので、指す先は 1 つに決まる。
 */
@Service
class QuizImportUseCase(
    private val quizRepository: QuizRepository,
    private val categoryRepository: CategoryRepository,
    private val difficultyRepository: DifficultyRepository,
    private val tenantTransaction: TenantTransaction,
) {
    fun import(rows: List<QuizImportRow>): Int = tenantTransaction.execute {
        val resolver = Resolver()
        val errors = mutableListOf<QuizImportRowError>()
        val quizzes = rows.mapIndexedNotNull { index, row ->
            val (quiz, messages) = validate(row, index, resolver)
            if (messages.isNotEmpty()) errors += QuizImportRowError(index, messages)
            quiz
        }
        if (errors.isNotEmpty()) throw QuizImportRejectedException(errors)

        quizzes.forEach(quizRepository::save)
        quizzes.size
    }

    /** 1 行を検証する。不正なら理由を返し、クイズは返さない。理由はできるだけまとめて返す */
    private fun validate(row: QuizImportRow, index: Int, resolver: Resolver): Pair<Quiz?, List<String>> {
        val messages = mutableListOf<String>()

        val category = resolver.category(row.category.trim())
        val difficulty = category?.let { resolver.difficulty(it, row.difficulty.trim()) }
        when {
            category == null -> messages += "カテゴリが見つかりません"
            difficulty == null -> messages += "難易度がこのカテゴリにありません"
        }

        val status = runCatching { QuizStatus.from(row.status.trim()) }
            .onFailure { messages += "状態は draft または published を指定してください" }
            .getOrNull()

        // 内容の検証はドメインに任せる。カテゴリや難易度が見つからなくても、内容の誤りもあわせて返す
        val quiz = runCatching {
            Quiz(
                categoryId = category?.id ?: UNRESOLVED,
                difficultyId = difficulty?.id ?: UNRESOLVED,
                question = row.question.trim(),
                explanation = row.explanation.trim(),
                choices = row.choices.map { Choice(body = it.body.trim(), isCorrect = it.isCorrect) },
                status = status ?: QuizStatus.DRAFT,
            )
        }.onFailure { e ->
            if (e !is IllegalArgumentException) throw e
            messages += e.message ?: "入力が不正です"
        }.getOrNull()

        if (category != null && quiz != null) {
            resolver.claim(category, quiz.question, index)?.let { messages += it }
        }

        return (quiz.takeIf { messages.isEmpty() }) to messages
    }

    /**
     * 名前の解決と重複の判定。行ごとに DB を引かないよう、カテゴリ単位で読んで持っておく。
     * 読むのは削除されていないものだけ（リポジトリがそう返す）。
     */
    private inner class Resolver {
        private val categories: Map<String, Category> by lazy { categoryRepository.findAll().associateBy { it.name } }
        private val difficulties = mutableMapOf<UUID, Map<String, Difficulty>>()
        private val existingQuestions = mutableMapOf<UUID, Set<String>>()
        private val claimed = mutableMapOf<Pair<UUID, String>, Int>()

        fun category(name: String): Category? = categories[name]

        fun difficulty(category: Category, name: String): Difficulty? {
            val categoryId = category.persistedId()
            return difficulties.getOrPut(categoryId) {
                difficultyRepository.findByCategoryId(categoryId).associateBy { it.name }
            }[name]
        }

        /** 同じカテゴリに同じ問題文がないかを確かめ、この行の問題文として押さえる。重複なら理由を返す */
        fun claim(category: Category, question: String, index: Int): String? {
            val categoryId = category.persistedId()
            val existing = existingQuestions.getOrPut(categoryId) {
                quizRepository.search(categoryId = categoryId).map { it.question.trim() }.toSet()
            }
            // 先に取り込んだ行と重複していても、この行の位置は押さえない。最初の行を指し続ける
            val first = if (question in existing) null else claimed.putIfAbsent(categoryId to question, index)
            return when {
                question in existing -> "同じカテゴリに、同じ問題文のクイズがすでにあります"

                // 位置は 1 始まりで伝える。画面は CSV の行番号に読み替える
                first != null -> "${first + 1} 件目と問題文が重複しています"

                else -> null
            }
        }

        private fun Category.persistedId(): UUID = requireNotNull(id) { "永続化されたカテゴリには ID があるはずです" }
    }

    companion object {
        /** 1 回に取り込める件数 */
        const val MAX_ROWS = 500

        /** カテゴリや難易度が見つからない行で、内容の検証だけを行うための仮の ID。保存はしない */
        private val UNRESOLVED = UUID(0, 0)
    }
}
