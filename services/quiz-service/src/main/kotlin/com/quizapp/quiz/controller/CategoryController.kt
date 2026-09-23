package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.CategoryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
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
 * カテゴリの CRUD。
 *
 * パスの `{slug}` はテナントの識別に使う。値の取り出しは
 * [com.quizapp.tenant.TenantResolutionFilter] が行うため、ここでは受け取らない。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/categories", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "カテゴリ", description = "管理者がカテゴリを CRUD する")
class CategoryController(private val useCase: CategoryUseCase) {

    @GetMapping
    @Operation(operationId = "listCategories", summary = "カテゴリ一覧")
    fun list(): List<CategoryResponse> = useCase.list().map(CategoryResponse::from)

    @GetMapping("/{id}")
    @Operation(operationId = "getCategory", summary = "カテゴリを 1 件取得")
    fun get(@PathVariable id: UUID): CategoryResponse = CategoryResponse.from(useCase.get(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createCategory", summary = "カテゴリを作成")
    fun create(@Valid @RequestBody request: CreateCategoryRequest): CategoryResponse = CategoryResponse.from(
        useCase.create(request.name, request.description, request.sortOrder),
    )

    @PutMapping("/{id}")
    @Operation(operationId = "updateCategory", summary = "カテゴリを更新")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateCategoryRequest): CategoryResponse =
        CategoryResponse.from(
            useCase.update(id, request.name, request.description, request.sortOrder),
        )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteCategory", summary = "カテゴリを削除")
    fun delete(@PathVariable id: UUID) = useCase.delete(id)
}
