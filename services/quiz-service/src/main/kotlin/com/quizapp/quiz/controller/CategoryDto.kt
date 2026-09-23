package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Category
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateCategoryRequest(
    @field:NotBlank(message = "カテゴリ名を入力してください")
    @field:Size(max = Category.MAX_NAME_LENGTH, message = "カテゴリ名は {max} 文字以内で入力してください")
    val name: String,
    @field:Size(max = 500, message = "説明は {max} 文字以内で入力してください")
    val description: String? = null,
    val sortOrder: Int = 0,
)

data class UpdateCategoryRequest(
    @field:NotBlank(message = "カテゴリ名を入力してください")
    @field:Size(max = Category.MAX_NAME_LENGTH, message = "カテゴリ名は {max} 文字以内で入力してください")
    val name: String,
    @field:Size(max = 500, message = "説明は {max} 文字以内で入力してください")
    val description: String? = null,
    val sortOrder: Int = 0,
)

data class CategoryResponse(val id: UUID, val name: String, val description: String?, val sortOrder: Int) {
    companion object {
        fun from(category: Category) = CategoryResponse(
            id = requireNotNull(category.id) { "永続化されたカテゴリには ID があるはずです" },
            name = category.name,
            description = category.description,
            sortOrder = category.sortOrder,
        )
    }
}
