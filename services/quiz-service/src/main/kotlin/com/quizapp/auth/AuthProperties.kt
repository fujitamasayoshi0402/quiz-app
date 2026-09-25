package com.quizapp.auth

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * アクセストークンを発行する認証基盤（ADR-0016）。
 *
 * **値が無ければ起動しない。** 空のまま起動すると、すべての要求が 401 になり、原因が起動のあとまで分からない。
 * マイグレーションのタスクも同じ設定で起動するため、どちらにも渡す。
 */
@Validated
@ConfigurationProperties("app.auth")
data class AuthProperties(
    /** JWT の発行者（例: `https://cognito-idp.<リージョン>.amazonaws.com/<User Pool の ID>`）。JWT の `iss` と比べる */
    @field:NotBlank val issuer: String,
    /** トークンを受け取る web のクライアント。Cognito のアクセストークンは `aud` を持たず、`client_id` に入る */
    @field:NotBlank val clientId: String,
)
