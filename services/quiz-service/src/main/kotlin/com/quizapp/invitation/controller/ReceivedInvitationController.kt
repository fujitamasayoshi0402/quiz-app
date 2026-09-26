package com.quizapp.invitation.controller

import com.quizapp.auth.MyTenantResponse
import com.quizapp.invitation.usecase.InvitationUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 招待を受け入れる（要件定義 U1）。
 *
 * **テナントの外に置く。** 受け入れる人はまだ所属しておらず、テナント配下では所属の判定で止まる。
 * 範囲は、トークンと、ログインしている人の確認済みのメールアドレスで絞る。
 * 認証は [com.quizapp.auth.AuthenticationRequiredInterceptor] がパスで要求する。
 */
@RestController
@RequestMapping("/api/me/invitations", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "利用者", description = "ログインしている利用者自身の情報")
class ReceivedInvitationController(private val useCase: InvitationUseCase) {

    @GetMapping("/{token}")
    @Operation(operationId = "getReceivedInvitation", summary = "受け取った招待。受け入れる前に招待先を確かめる")
    fun get(@PathVariable token: String): ReceivedInvitationResponse =
        ReceivedInvitationResponse.from(useCase.receive(token))

    @PostMapping("/{token}/accept")
    @Operation(operationId = "acceptInvitation", summary = "招待を受け入れ、テナントに所属する")
    fun accept(@PathVariable token: String): MyTenantResponse = MyTenantResponse.from(useCase.accept(token))
}
