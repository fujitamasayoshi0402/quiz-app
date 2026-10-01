package com.quizapp.notification.controller

import com.quizapp.notification.domain.SlackWebhookUrl
import com.quizapp.notification.usecase.SlackWebhookUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * クイズの追加・更新を知らせる、テナントの Slack の通知先（ADR-0022）。管理者だけが触れる。
 *
 * 設定した URL は、どの応答にも出さない。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/notifications/slack", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "通知", description = "クイズの追加・更新を知らせる Slack の通知先。設定した URL は返さない")
class SlackWebhookController(private val useCase: SlackWebhookUseCase) {

    @GetMapping
    @Operation(operationId = "getSlackWebhook", summary = "Slack の通知先を設定したかどうか")
    fun get(): SlackWebhookResponse = SlackWebhookResponse.from(useCase.find())

    @PutMapping
    @Operation(
        operationId = "configureSlackWebhook",
        summary = "Slack の Incoming Webhook の URL を設定する。すでにあれば置き換える",
    )
    fun configure(@Valid @RequestBody request: ConfigureSlackWebhookRequest): SlackWebhookResponse =
        SlackWebhookResponse.from(useCase.configure(SlackWebhookUrl(request.url)))

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeSlackWebhook", summary = "Slack の通知先を消す。設定していなくても成功する")
    fun remove() = useCase.remove()
}
