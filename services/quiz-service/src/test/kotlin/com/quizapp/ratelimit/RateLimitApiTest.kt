package com.quizapp.ratelimit

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.util.UUID

/**
 * 利用者ごとの流量の上限（DEV-125）を、要求の流れの中で確かめる。
 *
 * 上限を小さくし、戻らないようにしている（1 時間に 1 件）。利用者はテストごとに作る。
 * 数え方そのもの（戻る速さ、片付け）は `RateLimiterTest` が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            TestPostgres.configure(registry)
            registry.add("app.rate-limit.enabled") { "true" }
            registry.add("app.rate-limit.per-user.capacity") { PER_USER }
            registry.add("app.rate-limit.per-user.refill") { 1 }
            registry.add("app.rate-limit.per-user.per") { "1h" }
            registry.add("app.rate-limit.heavy.capacity") { 1 }
            registry.add("app.rate-limit.heavy.refill") { 1 }
            registry.add("app.rate-limit.heavy.per") { "1h" }
        }

        private const val PER_USER = 3
    }

    @Autowired private lateinit var mockMvc: MockMvc

    private fun call(path: String, user: UUID?): ResultActionsDsl = mockMvc.get(path) {
        user?.let { header("Authorization", TestAuth.bearer(it)) }
    }

    @Test
    @DisplayName("上限を超えると 429 と Retry-After を返す。ほかの利用者は使える")
    fun perUser() {
        val alice = TestAuth.createUser("アリス")
        val bob = TestAuth.createUser("ボブ")
        repeat(PER_USER) { call("/api/me/tenants", alice).andExpect { status { isOk() } } }

        call("/api/me/tenants", alice).andExpect {
            status { isTooManyRequests() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            header { string("Retry-After", "3600") }
            jsonPath("$.title") { value("要求が多すぎます") }
        }
        call("/api/me/tenants", bob).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("重い操作は、別の厳しい上限で数える。使い切っても、ほかの操作はできる")
    fun heavy() {
        val carol = TestAuth.createUser("キャロル")
        val token = "not-a-real-token"

        call("/api/me/invitations/$token", carol).andExpect { status { isNotFound() } }
        call("/api/me/invitations/$token", carol).andExpect { status { isTooManyRequests() } }
        call("/api/me/tenants", carol).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("所属していないテナントでも、所属を確かめる前に数える")
    fun beforeTenantAccess() {
        val dave = TestAuth.createUser("デイブ")
        val tenant = TestTenant.create()

        repeat(PER_USER) { call("/api/t/${tenant.slug}/play/categories", dave).andExpect { status { isNotFound() } } }
        call("/api/t/${tenant.slug}/play/categories", dave).andExpect { status { isTooManyRequests() } }
    }

    @Test
    @DisplayName("アクセストークンのない要求は数えない。401 のまま")
    fun unauthenticated() {
        repeat(PER_USER + 1) { call("/api/me/tenants", null).andExpect { status { isUnauthorized() } } }
    }
}
