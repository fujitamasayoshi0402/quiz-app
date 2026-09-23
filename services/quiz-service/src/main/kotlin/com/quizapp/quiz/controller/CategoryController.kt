package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.CategoryUseCase
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
 * カテゴリの CRUD。
 *
 * パスの `{slug}` はテナントの識別に使う。値の取り出しは
 * [com.quizapp.tenant.TenantResolutionFilter] が行うため、ここでは受け取らない。
 */
@RestController
@RequestMapping("/api/t/{slug}/categories")
class CategoryController(private val useCase: CategoryUseCase) {

    @GetMapping
    fun list(): List<CategoryResponse> = useCase.list().map(CategoryResponse::from)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): CategoryResponse = CategoryResponse.from(useCase.get(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateCategoryRequest): CategoryResponse =
        CategoryResponse.from(
            useCase.create(request.name, request.description, request.sortOrder),
        )

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateCategoryRequest,
    ): CategoryResponse =
        CategoryResponse.from(
            useCase.update(id, request.name, request.description, request.sortOrder),
        )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = useCase.delete(id)
}
