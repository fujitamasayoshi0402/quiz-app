package com.quizapp.answer.controller

import com.quizapp.answer.usecase.AnswerResult
import com.quizapp.answer.usecase.AttemptResult
import com.quizapp.answer.usecase.AttemptUseCase
import com.quizapp.answer.usecase.AttemptView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 挑戦（一般ユーザー向け）。
 *
 * 管理用のクイズ API とは別のエンドポイントにしている。
 * 同じ `/quizzes` に混ぜると、**正解を含む応答と含まない応答が 1 つの URL に同居**し、
 * 権限の掛け違いで正解が漏れる余地が生まれる。
 *
 * 学習モードと模試モードで API を分けない。どちらも 1 問ずつ回答を送り、
 * 応答に正誤と解説を含める。まとめて見せるかどうかはクライアントが決める。
 */
@RestController
@RequestMapping("/api/t/{slug}/play/attempts")
class AttemptController(private val useCase: AttemptUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@Valid @RequestBody request: StartAttemptRequest): AttemptView =
        useCase.start(request.toCriteria(), request.discardInProgress)

    /** 中断中の挑戦。なければ 204。 */
    @GetMapping("/current")
    fun current(): ResponseEntity<AttemptView> =
        useCase.current()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @GetMapping("/{attemptId}")
    fun resume(@PathVariable attemptId: UUID): AttemptView = useCase.resume(attemptId)

    @PostMapping("/{attemptId}/answers")
    fun answer(
        @PathVariable attemptId: UUID,
        @Valid @RequestBody request: AnswerRequest,
    ): AnswerResult = useCase.answer(attemptId, request.quizId, request.choiceId)

    @PostMapping("/{attemptId}/complete")
    fun complete(@PathVariable attemptId: UUID): AttemptResult = useCase.complete(attemptId)

    @PostMapping("/{attemptId}/abandon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun abandon(@PathVariable attemptId: UUID) = useCase.abandon(attemptId)
}
