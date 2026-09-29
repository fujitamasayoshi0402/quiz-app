package com.quizapp.quiz.domain

import java.util.UUID

/**
 * 難易度の永続化。取得系は削除済みを含まない（[CategoryRepository] と同じ方針）。
 */
interface DifficultyRepository {
    /** 指定カテゴリの難易度を、レベルと並び順で返す。 */
    fun findByCategoryId(categoryId: UUID): List<Difficulty>

    fun findById(id: UUID): Difficulty?

    fun save(difficulty: Difficulty): Difficulty

    /** [ids] の順に、並び順を 0 から振り直す。表示はレベル順が先なので、同じレベルの中の順だけが意味を持つ */
    fun reorder(ids: List<UUID>)
}
