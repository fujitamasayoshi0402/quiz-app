package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.DifficultyUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/t/{slug}/categories/{categoryId}/difficulties")
class DifficultyController(private val useCase: DifficultyUseCase) {

    @GetMapping
    fun list(@PathVariable categoryId: UUID): List<DifficultyResponse> =
        useCase.list(categoryId).map(DifficultyResponse::from)

    @GetMapping("/{id}")
    fun get(@PathVariable categoryId: UUID, @PathVariable id: UUID): DifficultyResponse =
        DifficultyResponse.from(useCase.get(categoryId, id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: SaveDifficultyRequest,
    ): DifficultyResponse = DifficultyResponse.from(
        useCase.create(categoryId, request.name, request.level, request.sortOrder, request.description),
    )

    @PutMapping("/{id}")
    fun update(
        @PathVariable categoryId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SaveDifficultyRequest,
    ): DifficultyResponse = DifficultyResponse.from(
        useCase.update(categoryId, id, request.name, request.level, request.sortOrder, request.description),
    )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable categoryId: UUID, @PathVariable id: UUID) = useCase.delete(categoryId, id)
}
