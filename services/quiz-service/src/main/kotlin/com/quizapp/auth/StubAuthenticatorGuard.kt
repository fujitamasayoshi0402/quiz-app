package com.quizapp.auth

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 本番相当のプロファイルでスタブ認証が有効なら、**起動を失敗させる。**
 *
 * [StubAuthenticator] の [org.springframework.context.annotation.Profile] 指定が
 * 効いていることを確かめるための二重化。
 * プロファイル名の書き間違いのような単純な誤りが、そのまま全テナントの情報漏洩になる。
 *
 * 認証実装が 1 つも無い場合は、Spring が [Authenticator] を注入できずに起動しない。
 * こちらも安全側に倒れる。
 */
@Component
class StubAuthenticatorGuard(private val authenticator: Authenticator, private val environment: Environment) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun verify() {
        val production = environment.activeProfiles.any { it in PRODUCTION_PROFILES }
        check(!(production && authenticator is StubAuthenticator)) {
            "本番相当のプロファイル（${environment.activeProfiles.joinToString()}）で" +
                "スタブ認証が有効になっています。誰にでもなりすませるため起動を中止します"
        }
        if (authenticator is StubAuthenticator) {
            log.warn("スタブ認証が有効です。X-User-Id ヘッダで利用者を偽装できます（Phase 3 で撤去）")
        }
    }

    private companion object {
        val PRODUCTION_PROFILES = setOf("prod", "stg")
    }
}
