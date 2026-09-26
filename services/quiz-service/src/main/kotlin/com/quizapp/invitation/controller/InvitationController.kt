package com.quizapp.invitation.controller

import com.quizapp.auth.TenantRole
import com.quizapp.invitation.usecase.InvitationUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
 * 招待を作り、取り消す（要件定義 A6）。管理者だけが触れる。
 *
 * 招待のメールは送らない。作った直後に返すトークンから画面がリンクを作り、管理者が相手に渡す（ADR-0016）。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/invitations", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "招待", description = "テナントへの招待を作り、取り消す。招待のトークンは作った直後にだけ返す")
class InvitationController(private val useCase: InvitationUseCase) {

    @GetMapping
    @Operation(operationId = "listInvitations", summary = "受け入れを待っている招待（期限切れを含む）")
    fun list(): List<InvitationResponse> = useCase.list().map(InvitationResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "createInvitation",
        summary = "招待を作る。同じアドレスへの受け入れ待ちの招待は取り消される",
    )
    fun create(@Valid @RequestBody request: CreateInvitationRequest): CreatedInvitationResponse =
        CreatedInvitationResponse.from(useCase.create(request.email, TenantRole.from(request.role)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeInvitation", summary = "招待を取り消す")
    fun revoke(@PathVariable id: UUID) = useCase.revoke(id)
}
