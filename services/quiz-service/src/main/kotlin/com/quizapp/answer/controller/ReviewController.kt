package com.quizapp.answer.controller

import com.quizapp.answer.usecase.ReviewSummary
import com.quizapp.answer.usecase.ReviewUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 今日の復習（一般ユーザー向け）。**見られるのは自分の記録だけ。** 利用者を指定するパラメータを持たない */
@RestController
@RequestMapping("/api/t/{slug}/play/review", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "今日の復習", description = "間隔反復で、次に出す日が来た問題。解くのは出題対象 `review` で挑戦を始める（ADR-0033）")
class ReviewController(private val useCase: ReviewUseCase) {

    @GetMapping
    @Operation(
        operationId = "getReviewSummary",
        summary = "今日の復習の件数",
        description = "次に出す日が今日（日本時間）までの、いま出題できる問題の数。0 件なら、次に復習が来る日も返す",
    )
    fun summary(): ReviewSummary = useCase.summary()
}
