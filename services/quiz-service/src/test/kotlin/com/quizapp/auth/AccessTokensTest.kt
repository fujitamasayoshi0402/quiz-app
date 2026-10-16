package com.quizapp.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class AccessTokensTest {

    private val issuer = "https://issuer.test/pool"

    private fun token(clientId: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .issuer(issuer)
        .issuedAt(Instant.now().minusSeconds(1))
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("token_use", "access")
        .claim("client_id", clientId)
        .build()

    private fun accepts(properties: AuthProperties, clientId: String): Boolean =
        !AccessTokens.validator(properties).validate(token(clientId)).hasErrors()

    @Test
    @DisplayName("web とモバイルアプリのクライアントのトークンを受け付ける")
    fun acceptsBothClients() {
        val properties = AuthProperties(issuer, clientId = "web", mobileClientId = "mobile")

        assertThat(accepts(properties, "web")).isTrue()
        assertThat(accepts(properties, "mobile")).isTrue()
        assertThat(accepts(properties, "other")).isFalse()
    }

    @Test
    @DisplayName("モバイルアプリのクライアントが無いとき、client_id が空のトークンを受け付けない")
    fun rejectsBlankClientIdWithoutMobileClient() {
        val properties = AuthProperties(issuer, clientId = "web")

        assertThat(accepts(properties, "web")).isTrue()
        assertThat(accepts(properties, "")).isFalse()
    }
}
