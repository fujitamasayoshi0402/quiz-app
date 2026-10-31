package com.quizapp.warmup

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
import org.springframework.test.web.servlet.post

/**
 * DB を先に起こす API（`POST /api/warm-up`、DEV-217）。回数の上限は `DatabaseWarmUpTest` が見る。
 *
 * `/api` 配下で認証を求めない唯一の API。除外がほかのパスへ広がっていないことも、ここで見る
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarmUpApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("トークンがなくても受け付け、何も返さない")
    fun acceptsWithoutToken() {
        mockMvc.post(WarmUpController.PATH).andExpect {
            status { isNoContent() }
            content { string("") }
        }
    }

    @Test
    @DisplayName("ほかの API は、これまでどおり認証を求める")
    fun otherApisStillRequireAuthentication() {
        mockMvc.get("/api/me/tenants").andExpect { status { isUnauthorized() } }
        mockMvc.post("${WarmUpController.PATH}/other").andExpect { status { isUnauthorized() } }
    }
}
