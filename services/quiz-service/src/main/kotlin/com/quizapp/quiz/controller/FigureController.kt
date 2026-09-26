package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.FigureUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 解説図を置く・原本を取り出す・消す（ADR-0017）。SVG を取りに行く URL は [PlayFigureController] が出す。
 *
 * 図は書き換えない。描き直した図は、新しい図として置く。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/figures", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "解説図", description = "管理者が解説図（draw.io の原本と SVG）を置く")
class FigureController(private val useCase: FigureUseCase) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "createFigure",
        summary = "解説図を置く",
        description = "SVG は無害化しない。配るときに、アプリと別のオリジンから、スクリプトを止めるヘッダを付けて返す",
    )
    fun create(@RequestBody request: CreateFigureRequest): FigureResponse =
        FigureResponse(useCase.create(request.source, request.svg))

    @GetMapping("/{id}/source")
    @Operation(operationId = "getFigureSource", summary = "解説図の原本（draw.io）を取得")
    fun source(@PathVariable id: UUID): FigureSourceResponse = FigureSourceResponse(useCase.source(id))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "deleteFigure",
        summary = "解説図を消す",
        description = "論理削除ではない。発行済みの URL は期限（最長 10 分）まで使える",
    )
    fun delete(@PathVariable id: UUID) = useCase.delete(id)
}
