package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategorySummary
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateCategoryRequest(
    @field:NotBlank(message = "カテゴリ名を入力してください")
    @field:Size(max = Category.MAX_NAME_LENGTH, message = "カテゴリ名は {max} 文字以内で入力してください")
    val name: String,
    @field:Size(max = 500, message = "説明は {max} 文字以内で入力してください")
    val description: String? = null,
)

data class UpdateCategoryRequest(
    @field:NotBlank(message = "カテゴリ名を入力してください")
    @field:Size(max = Category.MAX_NAME_LENGTH, message = "カテゴリ名は {max} 文字以内で入力してください")
    val name: String,
    @field:Size(max = 500, message = "説明は {max} 文字以内で入力してください")
    val description: String? = null,
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

/** 管理画面のカテゴリ一覧。クイズの数を添える */
data class CategorySummaryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val sortOrder: Int,
    @field:Schema(description = "クイズの数。下書きを含み、削除済みは含まない")
    val quizCount: Int,
    @field:Schema(description = "クイズのうち、公開しているものの数")
    val publishedQuizCount: Int,
) {
    companion object {
        fun from(summary: CategorySummary) = CategorySummaryResponse(
            id = requireNotNull(summary.category.id) { "永続化されたカテゴリには ID があるはずです" },
            name = summary.category.name,
            description = summary.category.description,
            sortOrder = summary.category.sortOrder,
            quizCount = summary.quizCount,
            publishedQuizCount = summary.publishedQuizCount,
        )
    }
}

/** カテゴリや難易度を並べ替える。今あるものの ID を、並べたい順にすべて送る */
data class ReorderRequest(
    @field:ArraySchema(arraySchema = Schema(description = "並べたい順の ID。今あるものをちょうど 1 回ずつ含める"))
    val ids: List<UUID>,
)
