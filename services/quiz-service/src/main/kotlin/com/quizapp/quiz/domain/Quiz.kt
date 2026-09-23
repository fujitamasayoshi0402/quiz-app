package com.quizapp.quiz.domain

import java.util.UUID

enum class QuizStatus {
    /** 作りかけ。出題されない。不完全な状態でも保存できる */
    DRAFT,

    /** 公開済み。出題の対象になる */
    PUBLISHED,
    ;

    companion object {
        fun from(value: String): QuizStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("状態は draft または published を指定してください: $value")
    }
}

/**
 * クイズの選択肢。クイズに完全に従属するため、単体では意味を持たない。
 *
 * [id] を持つのは、出題したあとに「どれを選んだか」を受け取る必要があるため。
 * 新規作成時は null で、永続化されると採番される。
 */
data class Choice(val id: UUID? = null, val body: String, val isCorrect: Boolean) {
    init {
        require(body.isNotBlank()) { "選択肢の本文を入力してください" }
        require(body.length <= MAX_BODY_LENGTH) { "選択肢は $MAX_BODY_LENGTH 文字以内で入力してください" }
    }

    companion object {
        const val MAX_BODY_LENGTH = 500
    }
}

/**
 * 4 択クイズ。選択肢を含めて 1 つの集約として扱う。
 *
 * **「選択肢はちょうど 4 つ」「正解はちょうど 1 つ」は公開時の不変条件**とする。
 * 下書きは作りかけを保存する場所なので、選択肢が揃っていなくても保存できる。
 * この 2 つが揃っていないクイズは出題も採点も成立しないため、公開させない。
 *
 * 行数に関する制約は DB では表現できない（`CHECK` は行をまたげない）。
 * 正解が 2 つ以上ある状態は部分ユニークインデックスが拒否するが、
 * 「4 つちょうど」と「正解が 0 個でない」はここで守る。
 */
data class Quiz(
    val id: UUID? = null,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    val explanation: String,
    val choices: List<Choice> = emptyList(),
    val status: QuizStatus = QuizStatus.DRAFT,
) {
    init {
        require(question.isNotBlank()) { "問題文を入力してください" }
        require(question.length <= MAX_QUESTION_LENGTH) { "問題文は $MAX_QUESTION_LENGTH 文字以内で入力してください" }
        require(choices.size <= CHOICE_COUNT) { "選択肢は $CHOICE_COUNT 個までです" }
        require(choices.count { it.isCorrect } <= 1) { "正解は 1 つだけ指定してください" }

        if (status == QuizStatus.PUBLISHED) {
            require(choices.size == CHOICE_COUNT) { "公開するには選択肢が $CHOICE_COUNT 個必要です" }
            require(choices.count { it.isCorrect } == 1) { "公開するには正解を 1 つ指定してください" }
            require(explanation.isNotBlank()) { "公開するには解説を入力してください" }
        }
    }

    companion object {
        const val CHOICE_COUNT = 4
        const val MAX_QUESTION_LENGTH = 2000
    }
}
