package com.quizapp.tenant.controller

import com.quizapp.tenant.domain.TenantSettings
import com.quizapp.tenant.domain.TenantVisibility
import com.quizapp.tenant.usecase.TenantSettingsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * テナントの設定。いまは公開設定（`private` / `public`）だけ（ADR-0025）。管理者だけが触れる。
 *
 * `public` にすると、ログインした人が公開テナントの一覧から見つけて、一般ユーザーとして参加できる。
 * `private` に戻しても、参加した人は残る
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/settings", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "テナントの設定", description = "公開設定。public にすると、ログインした人が一覧から見つけて参加できる")
class TenantSettingsController(private val useCase: TenantSettingsUseCase) {

    @GetMapping
    @Operation(operationId = "getTenantSettings", summary = "テナントの設定")
    fun get(): TenantSettingsResponse = TenantSettingsResponse.from(useCase.find())

    @PutMapping
    @Operation(operationId = "updateTenantSettings", summary = "テナントの設定を置き換える")
    fun update(@Valid @RequestBody request: UpdateTenantSettingsRequest): TenantSettingsResponse =
        TenantSettingsResponse.from(useCase.update(TenantSettings(TenantVisibility.from(request.visibility))))
}

data class UpdateTenantSettingsRequest(
    @field:Pattern(regexp = "private|public", message = "公開設定は private か public を指定してください")
    val visibility: String,
)

data class TenantSettingsResponse(
    /** `private`（招待した人だけ）か `public`（ログインした人が自分で参加できる） */
    val visibility: String,
    /** 公開にできるか。利用者が作ったテナントは、非公開に限る（ADR-0028） */
    val canBePublic: Boolean,
) {
    companion object {
        fun from(settings: TenantSettings) = TenantSettingsResponse(settings.visibility.value, settings.canBePublic)
    }
}
