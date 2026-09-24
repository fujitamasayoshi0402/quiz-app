package com.quizapp.health

import com.quizapp.quiz.support.TestPostgres
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * コンテナのヘルスチェック（docker compose / 将来の ALB）が使うエンドポイント。
 *
 * - **認証なしで呼べる。** ヘルスチェックは利用者を名乗らない
 * - **DB に問い合わせない。** 定期的に叩かれても Aurora の自動一時停止を妨げないため
 * - ヘルスチェック以外の actuator は公開しない
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthCheckTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("認証なしで UP を返し、DB の状態は含めない")
    fun healthIsPublicAndDoesNotCheckDatabase() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components.db") { doesNotExist() }
        }
    }

    @Test
    @DisplayName("ヘルスチェック以外の actuator は公開しない")
    fun otherActuatorEndpointsAreHidden() {
        listOf("/actuator/env", "/actuator/beans", "/actuator/configprops").forEach {
            mockMvc.get(it).andExpect { status { isNotFound() } }
        }
    }
}
