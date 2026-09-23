package com.quizapp.answer.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import com.quizapp.answer.usecase.AnswerResult
import com.quizapp.answer.usecase.AttemptResult
import com.quizapp.answer.usecase.AttemptUseCase
import com.quizapp.answer.usecase.AttemptView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
@RequestMapping("/api/t/{slug}/play/attempts", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "挑戦", description = "出題・回答・結果。**出題の応答に正解と解説を含まない**")
class AttemptController(private val useCase: AttemptUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "startAttempt", summary = "挑戦を始める")
    fun start(@Valid @RequestBody request: StartAttemptRequest): AttemptView =
        useCase.start(request.toCriteria(), request.discardInProgress)

    /** 中断中の挑戦。なければ 204。 */
    @GetMapping("/current")
    @Operation(operationId = "getCurrentAttempt", summary = "中断中の挑戦を取得")
    fun current(): ResponseEntity<AttemptView> =
        useCase.current()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @GetMapping("/{attemptId}")
    @Operation(operationId = "resumeAttempt", summary = "挑戦を再開する")
    fun resume(@PathVariable attemptId: UUID): AttemptView = useCase.resume(attemptId)

    @PostMapping("/{attemptId}/answers")
    @Operation(operationId = "answerQuiz", summary = "1 問に回答する")
    fun answer(
        @PathVariable attemptId: UUID,
        @Valid @RequestBody request: AnswerRequest,
    ): AnswerResult = useCase.answer(attemptId, request.quizId, request.choiceId)

    @PostMapping("/{attemptId}/complete")
    @Operation(operationId = "completeAttempt", summary = "挑戦を終えて結果を取得")
    fun complete(@PathVariable attemptId: UUID): AttemptResult = useCase.complete(attemptId)

    @PostMapping("/{attemptId}/abandon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "abandonAttempt", summary = "挑戦を破棄する")
    fun abandon(@PathVariable attemptId: UUID) = useCase.abandon(attemptId)
}
