package com.quizapp.tenant.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID

/**
 * テナントの公開設定（ADR-0025）。
 *
 * 別のテナントの設定に触れないことは `TenantBoundaryApiTest`、管理者でない人が触れないことはパスの規約が見る
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantSettingsApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin().join(TestAuth.MEMBER)
    }

    @Test
    @DisplayName("作ったばかりのテナントは非公開")
    fun privateByDefault() {
        get().andExpect {
            status { isOk() }
            jsonPath("$.visibility") { value("private") }
        }
    }

    @Test
    @DisplayName("公開にして、非公開に戻せる。変えた値は DB に残る")
    fun switchesVisibility() {
        update("public").andExpect {
            status { isOk() }
            jsonPath("$.visibility") { value("public") }
        }
        assertThat(storedVisibility()).isEqualTo("public")
        get().andExpect { jsonPath("$.visibility") { value("public") } }

        update("private").andExpect { jsonPath("$.visibility") { value("private") } }
        assertThat(storedVisibility()).isEqualTo("private")
    }

    @Test
    @DisplayName("同じ値に置き換えても成功する")
    fun sameValueIsAccepted() {
        update("private").andExpect {
            status { isOk() }
            jsonPath("$.visibility") { value("private") }
        }
    }

    @Test
    @DisplayName("private と public 以外は 400 で、設定は変わらない")
    fun rejectsUnknownVisibility() {
        update("PUBLIC").andExpect { status { isBadRequest() } }
        update("unlisted").andExpect { status { isBadRequest() } }

        assertThat(storedVisibility()).isEqualTo("private")
    }

    @Test
    @DisplayName("一般ユーザーは読めず、変えられない")
    fun memberCannotTouch() {
        get(TestAuth.MEMBER).andExpect { status { isForbidden() } }
        update("public", TestAuth.MEMBER).andExpect { status { isForbidden() } }

        assertThat(storedVisibility()).isEqualTo("private")
    }

    private fun base() = "/api/t/${tenant.slug}/admin/settings"

    private fun get(user: UUID = TestAuth.ADMIN) = mockMvc.get(base()) {
        header("Authorization", TestAuth.bearer(user))
    }

    private fun update(visibility: String, user: UUID = TestAuth.ADMIN) = mockMvc.put(base()) {
        contentType = MediaType.APPLICATION_JSON
        content = """{"visibility":"$visibility"}"""
        header("Authorization", TestAuth.bearer(user))
    }

    private fun storedVisibility(): String? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT visibility FROM core.tenants WHERE id = ?",
        String::class.java,
        tenant.id,
    )
}
