package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.usecase.QuizUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * クイズの CRUD（管理者向け）。
 *
 * 下書きも含めて返す。出題 API（DEV-21）は公開済みのみを対象にする。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/quizzes")
class QuizController(private val useCase: QuizUseCase) {

    @GetMapping
    fun search(
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) difficultyId: UUID?,
        @RequestParam(required = false) status: String?,
    ): List<QuizResponse> =
        useCase.search(categoryId, difficultyId, status?.let(QuizStatus::from)).map(QuizResponse::from)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): QuizResponse = QuizResponse.from(useCase.get(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: SaveQuizRequest): QuizResponse = QuizResponse.from(
        useCase.create(
            categoryId = request.categoryId,
            difficultyId = request.difficultyId,
            question = request.question,
            explanation = request.explanation,
            choices = request.choices.map { it.toDomain() },
            status = request.statusAsDomain(),
        ),
    )

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: SaveQuizRequest): QuizResponse =
        QuizResponse.from(
            useCase.update(
                id = id,
                categoryId = request.categoryId,
                difficultyId = request.difficultyId,
                question = request.question,
                explanation = request.explanation,
                choices = request.choices.map { it.toDomain() },
                status = request.statusAsDomain(),
            ),
        )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = useCase.delete(id)
}
