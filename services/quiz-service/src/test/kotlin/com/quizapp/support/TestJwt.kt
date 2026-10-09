package com.quizapp.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/**
 * テストで使うアクセストークン。Cognito の代わりに、テストの中で作った鍵で署名する。
 *
 * 検証の条件（発行者、`token_use`、`client_id`、期限）は本物と同じものを使う（[TestAuthConfiguration]）。
 * 条件を外したトークンを作れば、拒否されることも確かめられる。
 */
object TestJwt {

    const val ISSUER = "https://issuer.test/pool"
    const val CLIENT_ID = "test-web-client"

    /** Cognito の GetUser の代わりに、確認済みのメールアドレスをトークンに入れて渡す（[TestAuthConfiguration]） */
    const val EMAIL_CLAIM = "test_email"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()

    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    fun issue(
        subject: String,
        email: String? = null,
        tokenUse: String = "access",
        clientId: String = CLIENT_ID,
        issuer: String = ISSUER,
        expiresAt: Instant = Instant.now().plusSeconds(TOKEN_LIFETIME_SECONDS),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .issueTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(expiresAt))
            .claim("token_use", tokenUse)
            .claim("client_id", clientId)
            .apply { email?.let { claim(EMAIL_CLAIM, it) } }
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
            .apply { sign(RSASSASigner(keyPair.private)) }
            .serialize()
    }

    private const val KEY_SIZE = 2048
    private const val TOKEN_LIFETIME_SECONDS = 300L
}
