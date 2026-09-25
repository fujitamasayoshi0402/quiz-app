package com.quizapp.auth

import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
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
 * OIDC の userinfo から引く。
 *
 * Cognito の API（`GetUser`）ではなく標準の OIDC を使う。認証基盤を替えても（ADR-0016）、ここは変わらない。
 * `GetUser` に要るスコープ（`aws.cognito.signin.user.admin`）は、トークンで利用者の属性を書き換えられるため、web に持たせない。
 */
@Component
class OidcUserProfiles(private val properties: AuthProperties) : UserProfiles {

    private val client = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(TIMEOUT)
                setReadTimeout(TIMEOUT)
            },
        )
        .build()

    /** userinfo の場所は発行者の設定（discovery）にある。最初に使うときに一度だけ読む */
    private val userInfoEndpoint: String by lazy {
        val configuration = client.get()
            .uri("${properties.issuer}/.well-known/openid-configuration")
            .retrieve()
            .body<Map<String, Any?>>()
        requireNotNull(configuration?.get("userinfo_endpoint") as? String) { "発行者の設定に userinfo_endpoint がありません" }
    }

    override fun fetch(accessToken: String): UserProfile {
        val claims = client.get()
            .uri(userInfoEndpoint)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .retrieve()
            .body<Map<String, Any?>>()
            .orEmpty()
        // Cognito は email_verified を文字列（"true"）で返す
        val verified = claims["email_verified"].toString().toBoolean()
        return UserProfile(email = (claims["email"] as? String)?.takeIf { verified })
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
