package com.quizapp.startup

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 本番相当のプロファイルでデモ用シードが読み込まれる設定なら、**起動を失敗させる。**
 *
 * シードは `application-dev.yml` でしか Flyway の locations に加わらないが、
 * `dev` と `prod` を同時に有効にすれば両方が効いてしまう。
 * 本番のデータベースにデモのテナントと利用者が入ると、**誰でもログインできる口ができる。**
 *
 * [com.quizapp.auth.StubAuthenticatorGuard] と同じ考え方で、
 * 設定の誤りを起動時に落とす。
 */
@Component
class SeedDataGuard(private val environment: Environment) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun verify() {
        val locations = environment.getProperty(FLYWAY_LOCATIONS).orEmpty()
        val seeded = locations.contains(SEED_LOCATION)
        val production = environment.activeProfiles.any { it in PRODUCTION_PROFILES }

        check(!(production && seeded)) {
            "本番相当のプロファイル（${environment.activeProfiles.joinToString()}）で" +
                "デモ用シードが有効になっています。デモの利用者が作られるため起動を中止します"
        }
        if (seeded) log.warn("デモ用シードが有効です（{}）", locations)
    }

    private companion object {
        const val FLYWAY_LOCATIONS = "spring.flyway.locations"
        const val SEED_LOCATION = "db/seed"
        val PRODUCTION_PROFILES = setOf("prod", "stg")
    }
}
