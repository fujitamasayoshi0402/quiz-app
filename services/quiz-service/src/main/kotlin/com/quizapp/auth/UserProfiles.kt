package com.quizapp.auth

import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration

/**
 * 認証基盤が持つ利用者の情報。**初めて見る利用者を作るときにだけ引く。**
 *
 * アクセストークンにはメールアドレスが載らないため、別に問い合わせる。
 */
interface UserProfiles {

    fun fetch(accessToken: String): UserProfile
}

/** [email] は確認済みのものだけ。確認されていなければ null */
data class UserProfile(val email: String?)

/**
 * Cognito の `GetUser` で引く。アクセストークンで呼べる API で、AWS の認証情報は要らない。
 *
 * 標準の OIDC の userinfo は使わない。userinfo は `openid` のスコープを持つトークンしか受け付けず、
 * API（InitiateAuth）で取ったトークン（スモークテストが使う）はこのスコープを持てない。
 * `GetUser` に要るスコープ（`aws.cognito.signin.user.admin`）は、トークンで自分の属性を書き換えられるが、
 * トークンはブラウザに渡らない（web のサーバーが持つ）ため、書き換えに使われる経路がない（ADR-0016）。
 */
@Component
class CognitoUserProfiles(properties: AuthProperties, private val objectMapper: ObjectMapper) : UserProfiles {

    /** User Pool の API のエンドポイント。発行者（`https://cognito-idp.<リージョン>.amazonaws.com/<User Pool の ID>`）と同じホスト */
    private val endpoint: URI = URI.create(properties.issuer).resolve("/")

    private val client = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(TIMEOUT)
                setReadTimeout(TIMEOUT)
            },
        )
        .build()

    /**
     * 要求も応答も文字列で扱う。Cognito の API は `application/x-amz-json-1.1` で、
     * JSON の変換器（`application/json` と `+json` だけを扱う）が受け付けない
     */
    override fun fetch(accessToken: String): UserProfile {
        val response = client.post()
            .uri(endpoint)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.GetUser")
            .contentType(AMZ_JSON)
            .body(objectMapper.writeValueAsString(mapOf("AccessToken" to accessToken)))
            .retrieve()
            .body<String>()
            .orEmpty()
        val attributes = objectMapper.readTree(response).path("UserAttributes")
            .associate { it.path("Name").asString() to it.path("Value").asString() }
        val verified = attributes["email_verified"].toBoolean()
        return UserProfile(email = attributes["email"]?.takeIf { verified && it.isNotBlank() })
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
        val AMZ_JSON: MediaType = MediaType.parseMediaType("application/x-amz-json-1.1")
    }
}
