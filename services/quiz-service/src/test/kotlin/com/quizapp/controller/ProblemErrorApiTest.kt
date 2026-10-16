package com.quizapp.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * アプリが返すエラーは、どれも Problem Details（`application/problem+json`）になる（DEV-148）。
 *
 * 画面は、Problem Details でない 500 / 503 を「手前の API Gateway が返した（タスクが止まっている）」と読む。
 * アプリの不具合がその形で返ると、止まっていると誤って案内する。
 *
 * `/error` へ回るのはサーブレットのコンテナなので、MockMvc ではなく、ポートを開いて呼ぶ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProblemErrorApiTest.FailingController::class)
class ProblemErrorApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    /** 想定していない例外を投げる。ApiExceptionHandler はこれを扱わない */
    @TestConfiguration
    @RestController
    class FailingController {
        @GetMapping("/api/me/test-unexpected-failure")
        fun fail(): String = throw UnsupportedOperationException("想定していない失敗")
    }

    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val client = HttpClient.newHttpClient()

    private fun call(method: String, path: String): HttpResponse<String> {
        TestAuth.ensureUsers()
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Authorization", TestAuth.bearer(TestAuth.createUser()))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, title: String) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.headers().firstValue("content-type")).hasValue("application/problem+json")
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("status").asInt()).isEqualTo(status)
        assertThat(body.path("title").asString()).isEqualTo(title)
    }

    @Test
    @DisplayName("想定していない例外は、500 の Problem Details で返る。例外の文は出さない")
    fun unexpectedException() {
        val response = call("GET", "/api/me/test-unexpected-failure")

        assertProblem(response, 500, "サーバー内部エラー")
        assertThat(response.body()).doesNotContain("想定していない失敗")
    }

    @Test
    @DisplayName("無いパスは、404 の Problem Details で返る。パスは返さない")
    fun notFound() {
        val response = call("GET", "/api/me/no-such-endpoint")

        assertProblem(response, 404, "リソースが見つかりません")
        assertThat(response.body()).doesNotContain("no-such-endpoint")
    }

    @Test
    @DisplayName("受け付けないメソッドは、405 の Problem Details で返る")
    fun methodNotAllowed() {
        assertProblem(call("DELETE", "/api/me/tenants"), 405, "この操作は受け付けていません")
    }
}
