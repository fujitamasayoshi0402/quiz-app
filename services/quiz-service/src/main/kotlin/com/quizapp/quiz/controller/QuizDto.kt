package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.usecase.QuizListItem
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class ChoiceRequest(
    @field:NotBlank(message = "選択肢の本文を入力してください")
    @field:Size(max = Choice.MAX_BODY_LENGTH, message = "選択肢は {max} 文字以内で入力してください")
    val body: String,
    // Kotlin の `isCorrect` は Java の getter 規約では `correct` と読まれる。
    // Jackson は Kotlin のプロパティ名で出すため、明示しないと定義と実際の JSON がずれる
    @get:Schema(name = "isCorrect")
    val isCorrect: Boolean = false,
) {
    fun toDomain() = Choice(body = body, isCorrect = isCorrect)
}

data class SaveQuizRequest(
    val categoryId: UUID,
    val difficultyId: UUID,
    @field:NotBlank(message = "問題文を入力してください")
    @field:Size(max = Quiz.MAX_QUESTION_LENGTH, message = "問題文は {max} 文字以内で入力してください")
    val question: String,
    @field:Schema(description = Quiz.EXPLANATION_FORMAT)
    val explanation: String = "",
    // 下書きでは選択肢が揃っていなくてよいため、件数の検証はドメイン側で状態に応じて行う
    @field:Valid
    val choices: List<ChoiceRequest> = emptyList(),
    val status: String = "draft",
) {
    fun statusAsDomain(): QuizStatus = QuizStatus.from(status)
}

data class ChoiceResponse(
    val id: UUID,
    val body: String,
    // Kotlin の `isCorrect` は Java の getter 規約では `correct` と読まれる。
    // Jackson は Kotlin のプロパティ名で出すため、明示しないと定義と実際の JSON がずれる
    @get:Schema(name = "isCorrect")
    val isCorrect: Boolean,
)

data class QuizResponse(
    val id: UUID,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    @field:Schema(description = Quiz.EXPLANATION_FORMAT)
    val explanation: String,
    val choices: List<ChoiceResponse>,
    val status: String,
    @field:Schema(description = SOURCE_URL_DESCRIPTION)
    val sourceUrl: String?,
) {
    companion object {
        fun from(quiz: Quiz) = QuizResponse(
            id = requireNotNull(quiz.id) { "永続化されたクイズには ID があるはずです" },
            categoryId = quiz.categoryId,
            difficultyId = quiz.difficultyId,
            question = quiz.question,
            explanation = quiz.explanation,
            choices = quiz.choices.map {
                ChoiceResponse(
                    id = requireNotNull(it.id) { "永続化された選択肢には ID があるはずです" },
                    body = it.body,
                    isCorrect = it.isCorrect,
                )
            },
            status = quiz.status.name.lowercase(),
            sourceUrl = quiz.sourceUrl,
        )

        const val SOURCE_URL_DESCRIPTION = "AI が技術ニュースから作ったクイズの、元にした発表の URL。人が作ったものは null（ADR-0031）"
    }
}

/**
 * 一覧の 1 件。[QuizResponse] の項目に、回答の集計を足す（DEV-158）。
 *
 * 1 件を返す API（取得・作成・更新）には載せない。集計は一覧で問題を見比べるためのもの。
 * [QuizResponse] に null を許す項目として足すと、どの API で載るのかが定義から読めなくなる。
 */
data class QuizListItemResponse(
    val id: UUID,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    @field:Schema(description = Quiz.EXPLANATION_FORMAT)
    val explanation: String,
    val choices: List<ChoiceResponse>,
    val status: String,
    @field:Schema(description = QuizResponse.SOURCE_URL_DESCRIPTION)
    val sourceUrl: String?,
    @field:Schema(description = "テナントの全員の回答の数。解き直しはそれぞれ数える")
    val answerCount: Int,
    @field:Schema(description = "回答のうち、正解だった数")
    val correctCount: Int,
) {
    companion object {
        fun from(item: QuizListItem) = QuizResponse.from(item.quiz).let {
            QuizListItemResponse(
                id = it.id,
                categoryId = it.categoryId,
                difficultyId = it.difficultyId,
                question = it.question,
                explanation = it.explanation,
                choices = it.choices,
                status = it.status,
                sourceUrl = it.sourceUrl,
                answerCount = item.answers.answered,
                correctCount = item.answers.correct,
            )
        }
    }
}
