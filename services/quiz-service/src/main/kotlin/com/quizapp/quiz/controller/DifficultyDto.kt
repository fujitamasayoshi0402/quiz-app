package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Difficulty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class SaveDifficultyRequest(
    @field:NotBlank(message = "難易度名を入力してください")
    @field:Size(max = Difficulty.MAX_NAME_LENGTH, message = "難易度名は {max} 文字以内で入力してください")
    val name: String,
    @field:Min(value = 1, message = "レベルは {value} 以上で入力してください")
    val level: Int,
    val sortOrder: Int = 0,
    @field:Size(max = 500, message = "説明は {max} 文字以内で入力してください")
    val description: String? = null,
)

data class DifficultyResponse(
    val id: UUID,
    val categoryId: UUID,
    val name: String,
    val level: Int,
    val sortOrder: Int,
    val description: String?,
) {
    companion object {
        fun from(difficulty: Difficulty) = DifficultyResponse(
            id = requireNotNull(difficulty.id) { "永続化された難易度には ID があるはずです" },
            categoryId = difficulty.categoryId,
            name = difficulty.name,
            level = difficulty.level,
            sortOrder = difficulty.sortOrder,
            description = difficulty.description,
        )
    }
}
