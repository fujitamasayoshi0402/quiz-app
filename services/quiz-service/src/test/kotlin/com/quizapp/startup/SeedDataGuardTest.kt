package com.quizapp.startup

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * デモ用シードが本番で読み込まれないことの検証。
 *
 * プロファイルを分けているだけでは、`dev` と `prod` を同時に有効にしたときに防げない。
 */
class SeedDataGuardTest {

    private fun environment(profile: String?, locations: String) = MockEnvironment().apply {
        profile?.let { setActiveProfiles(it) }
        setProperty("spring.flyway.locations", locations)
    }

    @Test
    @DisplayName("本番プロファイルでシードが読み込まれる設定なら起動を止める")
    fun seedInProductionFailsStartup() {
        listOf("prod", "stg").forEach { profile ->
            val environment = environment(profile, "classpath:db/migration,classpath:db/seed")

            assertThatThrownBy { SeedDataGuard(environment).verify() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("デモ用シードが有効")
        }
    }

    @Test
    @DisplayName("本番プロファイルでも、シードを読み込まない設定なら起動する")
    fun productionWithoutSeedStarts() {
        assertThatCode { SeedDataGuard(environment("prod", "classpath:db/migration")).verify() }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("開発プロファイルではシードを許す")
    fun seedIsAllowedInDevelopment() {
        assertThatCode { SeedDataGuard(environment("dev", "classpath:db/migration,classpath:db/seed")).verify() }
            .doesNotThrowAnyException()
    }
}
