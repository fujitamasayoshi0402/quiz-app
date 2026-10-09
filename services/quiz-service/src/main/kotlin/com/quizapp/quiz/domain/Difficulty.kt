package com.quizapp.quiz.domain

import java.util.UUID

/**
 * 難易度。カテゴリごとに定義する（docs/domain-model.md）。
 *
 * [level] は難しさの**大小を表す尺度**であり、一意な識別子ではない。
 * AWS のアソシエイト級（SAA / DVA / SOA）のように、同じレベルの難易度が複数並ぶ。
 * 同一レベル内の表示順は [sortOrder] で決める。
 */
data class Difficulty(
    val id: UUID? = null,
    val categoryId: UUID,
    val name: String,
    val level: Int,
    val sortOrder: Int = 0,
    val description: String? = null,
) {
    init {
        require(name.isNotBlank()) { "難易度名を入力してください" }
        require(name.length <= MAX_NAME_LENGTH) { "難易度名は $MAX_NAME_LENGTH 文字以内で入力してください" }
        require(level >= MIN_LEVEL) { "レベルは $MIN_LEVEL 以上で入力してください" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 50
        const val MIN_LEVEL = 1
    }
}
