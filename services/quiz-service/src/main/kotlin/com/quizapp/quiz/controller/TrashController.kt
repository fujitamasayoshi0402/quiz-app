package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.Trash
import com.quizapp.quiz.usecase.TrashUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 削除済みの一覧と復活。画面は `/t/{slug}/admin/trash`（docs/requirements.md）。
 *
 * 復活を各リソースの配下（`/admin/categories/{id}/restore` など）に置かず、ここへ集めている。
 * **削除済みを触る操作が 1 か所にまとまっていれば、通常の CRUD から削除済みが漏れていないかを確認しやすい。**
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/trash", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "削除済み", description = "論理削除したカテゴリ・難易度・クイズの一覧と復活")
class TrashController(private val useCase: TrashUseCase) {

    @Operation(operationId = "listTrash", summary = "削除済みの一覧")
    @GetMapping
    fun list(): Trash = useCase.list()

    @Operation(
        operationId = "restoreCategory",
        summary = "カテゴリを復活する（一緒に削除された難易度・クイズも戻る）",
    )
    @PostMapping("/categories/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreCategory(@PathVariable id: UUID) = useCase.restoreCategory(id)

    @Operation(operationId = "restoreDifficulty", summary = "難易度を復活する（一緒に削除されたクイズも戻る）")
    @PostMapping("/difficulties/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreDifficulty(@PathVariable id: UUID) = useCase.restoreDifficulty(id)

    @Operation(operationId = "restoreQuiz", summary = "クイズを復活する")
    @PostMapping("/quizzes/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreQuiz(@PathVariable id: UUID) = useCase.restoreQuiz(id)
}
