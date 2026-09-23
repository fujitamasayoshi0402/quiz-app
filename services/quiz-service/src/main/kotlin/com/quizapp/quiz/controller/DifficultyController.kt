package com.quizapp.quiz.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import com.quizapp.quiz.usecase.DifficultyUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 難易度の CRUD。
 *
 * 難易度はカテゴリ配下のリソースなので、URL もカテゴリの下に置く。
 * 独立した一覧画面を持たない方針（docs/requirements.md）と対応している。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/categories/{categoryId}/difficulties", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "難易度", description = "カテゴリ配下の難易度を CRUD する。体系はカテゴリごとに決める")
class DifficultyController(private val useCase: DifficultyUseCase) {

    @GetMapping
    @Operation(operationId = "listDifficulties", summary = "難易度一覧")
    fun list(@PathVariable categoryId: UUID): List<DifficultyResponse> =
        useCase.list(categoryId).map(DifficultyResponse::from)

    @GetMapping("/{id}")
    @Operation(operationId = "getDifficulty", summary = "難易度を 1 件取得")
    fun get(@PathVariable categoryId: UUID, @PathVariable id: UUID): DifficultyResponse =
        DifficultyResponse.from(useCase.get(categoryId, id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDifficulty", summary = "難易度を作成")
    fun create(
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: SaveDifficultyRequest,
    ): DifficultyResponse = DifficultyResponse.from(
        useCase.create(categoryId, request.name, request.level, request.sortOrder, request.description),
    )

    @PutMapping("/{id}")
    @Operation(operationId = "updateDifficulty", summary = "難易度を更新")
    fun update(
        @PathVariable categoryId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SaveDifficultyRequest,
    ): DifficultyResponse = DifficultyResponse.from(
        useCase.update(categoryId, id, request.name, request.level, request.sortOrder, request.description),
    )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteDifficulty", summary = "難易度を削除")
    fun delete(@PathVariable categoryId: UUID, @PathVariable id: UUID) = useCase.delete(categoryId, id)
}
