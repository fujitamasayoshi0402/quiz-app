package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.FigureUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * 解説図（SVG、画像、PDF）を取りに行く（ADR-0017、ADR-0020）。
 *
 * 所属と図を確かめてから、期限の短い署名付き URL へ 302 で送る。**SVG をこのオリジンから返さない。**
 * SVG はスクリプトを含められるため、アプリと別のオリジン（CloudFront）から配る。
 *
 * 画面は、本文の中に出す画像（[preview]）を `<img src>` に書き、押したときに図そのもの（[get]）を開く。
 * 2 つが違うのは PDF だけで、本文の中には 1 ページ目の画像を出す（ADR-0021）。
 *
 * 図の ID は、回答したあとの応答と管理 API でしか渡さない（DEV-70）。出題の時点で見られないことは、ID を渡さないことで守る。
 */
@RestController
@RequestMapping("/api/t/{slug}/play/figures")
@Tag(name = "解説図（出題）", description = "利用者が解説図を見る")
class PlayFigureController(private val useCase: FigureUseCase) {

    @GetMapping("/{id}")
    @Operation(operationId = "getFigure", summary = "解説図へ送る")
    @ApiResponse(
        responseCode = "302",
        description = "期限（5〜10 分）つきの署名付き URL へ送る",
        headers = [
            Header(
                name = "Location",
                description = "図（SVG、画像、PDF）の署名付き URL",
                schema = Schema(type = "string", format = "uri"),
            ),
        ],
    )
    fun get(@PathVariable id: UUID): ResponseEntity<Void> = redirect(useCase.url(id))

    @GetMapping("/{id}/preview")
    @Operation(
        operationId = "getFigurePreview",
        summary = "本文の中に出す解説図の画像へ送る",
        description = "PDF は 1 ページ目の画像へ、ほかの図は図そのものへ送る（ADR-0021）",
    )
    @ApiResponse(
        responseCode = "302",
        description = "期限（5〜10 分）つきの署名付き URL へ送る",
        headers = [
            Header(
                name = "Location",
                description = "本文の中に出す画像（SVG か画像）の署名付き URL",
                schema = Schema(type = "string", format = "uri"),
            ),
        ],
    )
    fun preview(@PathVariable id: UUID): ResponseEntity<Void> = redirect(useCase.previewUrl(id))

    private fun redirect(url: URI): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND)
        .location(url)
        // 利用者ごとの応答。Amplify の CDN に共有させない。URL の期限より十分に短くする
        .cacheControl(CacheControl.maxAge(REDIRECT_MAX_AGE).cachePrivate())
        .build()

    private companion object {
        val REDIRECT_MAX_AGE: Duration = Duration.ofMinutes(1)
    }
}
