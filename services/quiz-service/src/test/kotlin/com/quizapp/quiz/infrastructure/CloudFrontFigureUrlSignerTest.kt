package com.quizapp.quiz.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class CloudFrontFigureUrlSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    // 鍵はテストの実行時に作る。ソースにあるのは PEM の見出しだけ
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + // gitleaks:allow
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"

    private fun signer(now: String) = CloudFrontFigureUrlSigner(
        baseUrl = "https://figures.example.com/",
        keyPairId = "K2EXAMPLE",
        privateKeyPem = pem,
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
    )

    private fun URI.params(): Map<String, String> =
        rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    @DisplayName("図のドメインの下にキーを続け、期限・署名・鍵の ID を付ける")
    fun signsUrl() {
        val url = signer("2026-09-26T10:02:30Z").sign("svg/tenant/figure.svg")

        assertThat(
            "${url.scheme}://${url.host}${url.path}",
        ).isEqualTo("https://figures.example.com/svg/tenant/figure.svg")
        val params = url.params()
        // 10:00〜10:05 の区切りの終わり（10:05）から 5 分後
        assertThat(params["Expires"]).isEqualTo(Instant.parse("2026-09-26T10:10:00Z").epochSecond.toString())
        assertThat(params["Key-Pair-Id"]).isEqualTo("K2EXAMPLE")

        // CloudFront と同じ手順で確かめる。固定のポリシーを SHA1withRSA で署名し、URL 用に置き換えた Base64
        val policy = """{"Statement":[{"Resource":"https://figures.example.com/svg/tenant/figure.svg",""" +
            """"Condition":{"DateLessThan":{"AWS:EpochTime":${params["Expires"]}}}}]}"""
        val signature = Base64.getDecoder().decode(
            params.getValue("Signature").replace('-', '+').replace('_', '=').replace('~', '/'),
        )
        val verified = Signature.getInstance("SHA1withRSA").run {
            initVerify(keyPair.public)
            update(policy.toByteArray())
            verify(signature)
        }
        assertThat(verified).describedAs("公開鍵で署名を確かめられる").isTrue()
    }

    @Test
    @DisplayName("同じ 5 分の区切りの間は、同じ URL を返す。ブラウザのキャッシュが効く")
    fun sameUrlWithinWindow() {
        val first = signer("2026-09-26T10:00:00Z").sign("svg/a.svg")
        val last = signer("2026-09-26T10:04:59Z").sign("svg/a.svg")
        val next = signer("2026-09-26T10:05:00Z").sign("svg/a.svg")

        assertThat(last).isEqualTo(first)
        assertThat(next).isNotEqualTo(first)
    }

    @Test
    @DisplayName("期限は、発行から 5〜10 分後")
    fun expiresWithinTenMinutes() {
        listOf("2026-09-26T10:00:00Z", "2026-09-26T10:04:59Z").forEach { now ->
            val issued = Instant.parse(now)
            val expires = FigureUrlSigner.expiresAt(issued)
            assertThat(expires).isAfter(issued.plusSeconds(299)).isBeforeOrEqualTo(issued.plusSeconds(600))
        }
    }

    @Test
    @DisplayName("秘密鍵がなければ、起動の時点で止まる")
    fun requiresPrivateKey() {
        assertThatThrownBy {
            CloudFrontFigureUrlSigner("https://figures.example.com", "K2EXAMPLE", "", Clock.systemUTC())
        }.hasMessageContaining("FIGURES_CLOUDFRONT_PRIVATE_KEY")
    }
}
