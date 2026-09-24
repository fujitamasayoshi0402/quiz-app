package com.quizapp.quiz.domain

import java.util.UUID

/**
 * 出題条件の選択画面に並べるカテゴリ。**公開済みのクイズが 1 問以上あるものだけ**を含む。
 *
 * 管理用の [Category] とは型を分ける。一般ユーザーに下書きの存在や件数を見せないためで、
 * 出題の応答から正解を外すために [DeliveredQuiz] を分けているのと同じ考え方。
 */
data class PlayableCategory(
    val id: UUID,
    val name: String,
    val description: String?,
    val difficulties: List<PlayableDifficulty>,
) {
    val quizCount: Int get() = difficulties.sumOf { it.quizCount }
}

/** [quizCount] は公開済みのクイズの数。選択肢の横に出し、出題数を決める目安にする。 */
data class PlayableDifficulty(
    val id: UUID,
    val name: String,
    val level: Int,
    val description: String?,
    val quizCount: Int,
)

interface PlayableCategoryQuery {
    /** カテゴリの並び順、難易度のレベル順・並び順で返す。 */
    fun findAll(): List<PlayableCategory>
}
