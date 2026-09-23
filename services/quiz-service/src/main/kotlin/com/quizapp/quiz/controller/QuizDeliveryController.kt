package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.DeliveredQuiz
import com.quizapp.quiz.usecase.DeliveryMode
import com.quizapp.quiz.usecase.QuizDeliveryUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 出題（一般ユーザー向け）。
 *
 * 管理用のクイズ API とは別のエンドポイントにしている。
 * 同じ `/quizzes` に混ぜると、**正解を含むレスポンスと含まないレスポンスが 1 つの URL に同居**し、
 * 権限の掛け違いで正解が漏れる余地が生まれる。
 */
@RestController
@RequestMapping("/api/t/{slug}/play")
class QuizDeliveryController(private val useCase: QuizDeliveryUseCase) {

    @GetMapping("/quizzes")
    fun deliver(
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) difficultyId: UUID?,
        @RequestParam(required = false) level: Int?,
        @RequestParam(required = false, defaultValue = "random") mode: String,
        @RequestParam(required = false) limit: Int?,
    ): List<DeliveredQuiz> = useCase.deliver(
        categoryId = categoryId,
        difficultyId = difficultyId,
        level = level,
        mode = DeliveryMode.from(mode),
        limit = limit ?: QuizDeliveryUseCase.DEFAULT_LIMIT,
    )
}
