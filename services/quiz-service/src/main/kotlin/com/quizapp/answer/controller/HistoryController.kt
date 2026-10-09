package com.quizapp.answer.controller

import com.quizapp.answer.domain.AttemptCursor
import com.quizapp.answer.usecase.AttemptHistoryPage
import com.quizapp.answer.usecase.CategoryScore
import com.quizapp.answer.usecase.HistoryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 履歴（一般ユーザー向け）。**返すのは自分の記録だけ。**
 *
 * 利用者を指定するパラメータを持たない。持たせると、他人の ID を入れたときに見えないことを
 * 別に確かめる必要が生まれる。
 */
@RestController
@RequestMapping("/api/t/{slug}/play/history", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "履歴", description = "自分の過去の挑戦と、カテゴリ別の正答率")
class HistoryController(private val useCase: HistoryUseCase) {

    @GetMapping("/attempts")
    @Operation(
        operationId = "listCompletedAttempts",
        summary = "完了した挑戦の一覧（新しい順）",
        description = "途中でやめた挑戦は含まない。続きは、前の応答の `nextCursor` を `cursor` に渡して取る",
    )
    fun attempts(
        @Parameter(description = "前の応答の `nextCursor`。省略すると先頭から")
        @RequestParam(required = false)
        cursor: String?,
    ): AttemptHistoryPage = useCase.attempts(cursor?.let(AttemptCursor::decode))

    @GetMapping("/categories")
    @Operation(
        operationId = "listCategoryScores",
        summary = "カテゴリ別の正答率",
        description = "いま出題できるクイズへの、最新の回答だけで数える。回答していないカテゴリも含む",
    )
    fun categories(): List<CategoryScore> = useCase.categories()
}
