package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizStatus
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
    val explanation: String,
    val choices: List<ChoiceResponse>,
    val status: String,
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
        )
    }
}
