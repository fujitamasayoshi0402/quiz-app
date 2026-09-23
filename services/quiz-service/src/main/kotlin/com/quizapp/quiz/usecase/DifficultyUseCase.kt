package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.DifficultyRepository
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

    fun create(categoryId: UUID, name: String, level: Int, sortOrder: Int, description: String?): Difficulty =
        tenantTransaction.execute {
            requireCategory(categoryId)
            difficultyRepository.save(
                Difficulty(
                    categoryId = categoryId,
                    name = name,
                    level = level,
                    sortOrder = sortOrder,
                    description = description,
                ),
            )
        }

    fun update(
        categoryId: UUID,
        id: UUID,
        name: String,
        level: Int,
        sortOrder: Int,
        description: String?,
    ): Difficulty = tenantTransaction.execute {
        requireCategory(categoryId)
        findInCategory(categoryId, id)
        difficultyRepository.save(
            Difficulty(
                id = id,
                categoryId = categoryId,
                name = name,
                level = level,
                sortOrder = sortOrder,
                description = description,
            ),
        )
    }

    fun delete(categoryId: UUID, id: UUID) = tenantTransaction.executeWithoutResult {
        requireCategory(categoryId)
        findInCategory(categoryId, id)
        difficultyRepository.softDelete(id)
    }

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
