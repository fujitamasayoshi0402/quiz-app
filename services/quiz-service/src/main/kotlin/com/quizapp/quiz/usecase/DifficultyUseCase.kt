package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.DeletionImpact
import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.quiz.domain.Ordering
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 難易度の操作。すべてカテゴリ配下の操作として扱う。
 *
 * 親カテゴリの存在確認を必ず行う。確認を省くと、**他テナントのカテゴリ ID を指定されたときに
 * 空リストを返してしまい**、「そのカテゴリには難易度が無い」という誤った応答になる。
 * 存在しないものとして 404 を返すのが正しい。
 */
@Service
class DifficultyUseCase(
    private val difficultyRepository: DifficultyRepository,
    private val categoryRepository: CategoryRepository,
    private val deletion: DeletionRepository,
    private val tenantTransaction: TenantTransaction,
) {
    fun list(categoryId: UUID): List<Difficulty> = tenantTransaction.execute {
        requireCategory(categoryId)
        difficultyRepository.findByCategoryId(categoryId)
    }

    fun get(categoryId: UUID, id: UUID): Difficulty = tenantTransaction.execute {
        requireCategory(categoryId)
        findInCategory(categoryId, id)
    }

    /** 作った難易度は、同じレベルの末尾に置く（[Ordering]） */
    fun create(categoryId: UUID, name: String, level: Int, description: String?): Difficulty =
        tenantTransaction.execute {
            requireCategory(categoryId)
            difficultyRepository.save(
                Difficulty(
                    categoryId = categoryId,
                    name = name,
                    level = level,
                    sortOrder = nextSortOrder(categoryId),
                    description = description,
                ),
            )
        }

    /** 並び順は変えない。レベルを変えたときだけ、新しいレベルの末尾に置く。元の位置は、別のレベルの中では意味を持たない */
    fun update(categoryId: UUID, id: UUID, name: String, level: Int, description: String?): Difficulty =
        tenantTransaction.execute {
            requireCategory(categoryId)
            val current = findInCategory(categoryId, id)
            val sortOrder = if (current.level == level) current.sortOrder else nextSortOrder(categoryId)
            difficultyRepository.save(
                current.copy(name = name, level = level, sortOrder = sortOrder, description = description),
            )
        }

    /**
     * カテゴリの今ある難易度の ID を、並べたい順にすべて受け取る。1 つでも過不足があれば受け付けない。
     * 表示はレベル順が先なので、意味を持つのは同じレベルの中の順だけ
     */
    fun reorder(categoryId: UUID, ids: List<UUID>) = tenantTransaction.executeWithoutResult {
        requireCategory(categoryId)
        Ordering.requireSameItems(ids, difficultyRepository.findByCategoryId(categoryId).mapNotNull { it.id })
        difficultyRepository.reorder(ids)
    }

    /** 削除したときに巻き込むクイズの数。 */
    fun deletionImpact(categoryId: UUID, id: UUID): DeletionImpact = tenantTransaction.execute {
        requireCategory(categoryId)
        deletion.impactOfDifficulty(categoryId, id) ?: throw DifficultyNotFoundException(id)
    }

    /** その難易度を使うクイズも一緒に削除する。難易度を失ったクイズは出題も編集もできないため。 */
    fun delete(categoryId: UUID, id: UUID) = tenantTransaction.executeWithoutResult {
        requireCategory(categoryId)
        findInCategory(categoryId, id)
        deletion.deleteDifficulty(id)
    }

    private fun nextSortOrder(categoryId: UUID): Int =
        Ordering.next(difficultyRepository.findByCategoryId(categoryId).map { it.sortOrder })

    private fun requireCategory(categoryId: UUID) {
        categoryRepository.findById(categoryId) ?: throw CategoryNotFoundException(categoryId)
    }

    /**
     * URL のカテゴリと難易度の所属が一致することを確認する。
     * 一致を確かめないと、別カテゴリの難易度を URL 上は自カテゴリのものとして操作できてしまう。
     */
    private fun findInCategory(categoryId: UUID, id: UUID): Difficulty {
        val difficulty = difficultyRepository.findById(id) ?: throw DifficultyNotFoundException(id)
        if (difficulty.categoryId != categoryId) throw DifficultyNotFoundException(id)
        return difficulty
    }
}

class DifficultyNotFoundException(val id: UUID) : RuntimeException("難易度が見つかりません: $id")
