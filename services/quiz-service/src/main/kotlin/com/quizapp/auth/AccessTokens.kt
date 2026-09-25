package com.quizapp.auth

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/**
 * アクセストークン（JWT）の検証。
 *
 * 署名の鍵は発行者の JWKS から取る。取るのは最初に検証するときで、起動のときには認証基盤につながない。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class AccessTokenConfiguration {

    @Bean
    fun accessTokenDecoder(properties: AuthProperties): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("${properties.issuer}/.well-known/jwks.json").build().apply {
            setJwtValidator(AccessTokens.validator(properties))
        }
}

object AccessTokens {

    /**
     * 署名のほかに確かめること。
     *
     * - 発行者と有効期限
     * - **アクセストークンであること**（`token_use`）。Cognito の ID トークンも同じ鍵で署名されている
     * - **この web のクライアントに発行されたこと**（`client_id`）。同じ User Pool の別のクライアントのトークンを受け付けない
     */
    fun validator(properties: AuthProperties): OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(properties.issuer),
        JwtClaimValidator<String>("token_use") { it == "access" },
        JwtClaimValidator<String>("client_id") { it == properties.clientId },
    )
}
