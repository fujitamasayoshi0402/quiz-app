package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

/**
 * スタブ認証が本番で有効にならないことの検証。
 *
 * [org.springframework.context.annotation.Profile] による除外が効いていることは、
 * プロファイル名を書き間違えれば崩れる。**設定の誤りが情報漏洩に直結する**ため、
 * 起動を止める側も動くことを確かめておく。
 */
class StubAuthenticatorGuardTest {

    private val realAuthenticator = object : Authenticator {
        override fun authenticate(request: HttpServletRequest): UUID? = null
    }

    @Test
    @DisplayName("本番プロファイルでスタブが有効なら起動を止める")
    fun stubInProductionFailsStartup() {
        listOf("prod", "stg").forEach { profile ->
            val environment = MockEnvironment().apply { setActiveProfiles(profile) }

            assertThatThrownBy { StubAuthenticatorGuard(StubAuthenticator(), environment).verify() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("スタブ認証が有効")
        }
    }

    @Test
    @DisplayName("本番プロファイルでも、本物の認証なら起動する")
    fun realAuthenticatorInProductionStarts() {
        val environment = MockEnvironment().apply { setActiveProfiles("prod") }

        assertThatCode { StubAuthenticatorGuard(realAuthenticator, environment).verify() }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("プロファイル未指定の開発環境ではスタブを許す")
    fun stubIsAllowedInDevelopment() {
        assertThatCode { StubAuthenticatorGuard(StubAuthenticator(), MockEnvironment()).verify() }
            .doesNotThrowAnyException()
    }
}
