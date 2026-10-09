package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableDifficulty
import com.quizapp.quiz.usecase.PlayableCategoryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 出題条件の選択肢（一般ユーザー向け）。
 *
 * 管理用の `/admin/categories` を一般ユーザーに開放せず、別のエンドポイントにしている。
 * 管理用は下書きしかないカテゴリも返すため、**公開前の内容の存在が漏れる**。
 */
@RestController
@RequestMapping("/api/t/{slug}/play/categories", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "出題条件", description = "公開済みのクイズがあるカテゴリと難易度")
class PlayCategoryController(private val useCase: PlayableCategoryUseCase) {

    @GetMapping
    @Operation(operationId = "listPlayableCategories", summary = "出題できるカテゴリと難易度の一覧")
    fun list(): List<PlayableCategoryResponse> = useCase.list().map(PlayableCategoryResponse::from)
}

data class PlayableCategoryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val quizCount: Int,
    val difficulties: List<PlayableDifficultyResponse>,
) {
    companion object {
        fun from(category: PlayableCategory) = PlayableCategoryResponse(
            id = category.id,
            name = category.name,
            description = category.description,
            quizCount = category.quizCount,
            difficulties = category.difficulties.map(PlayableDifficultyResponse::from),
        )
    }
}

data class PlayableDifficultyResponse(
    val id: UUID,
    val name: String,
    val level: Int,
    val description: String?,
    val quizCount: Int,
) {
    companion object {
        fun from(difficulty: PlayableDifficulty) = PlayableDifficultyResponse(
            id = difficulty.id,
            name = difficulty.name,
            level = difficulty.level,
            description = difficulty.description,
            quizCount = difficulty.quizCount,
        )
    }
}
