package com.quizapp.auth

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * テナント配下のエンドポイントに対する認証・認可の検証。
 *
 * 個々の API のテストは「正しい利用者」で叩いている。**ここだけが、誰が何を触れるかを見る。**
 *
 * 返す状態を使い分けている点も確認する。
 * - 所属していないテナント … **404**。403 だとテナントが実在することが分かる
 * - 所属しているが管理者ではない … 403。テナントの存在はすでに知っている
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var otherTenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin().join(TestAuth.MEMBER)
        // 管理者も所属していないテナント。OUTSIDER はどちらにも所属させない
        otherTenant = TestTenant.create()

        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        val category = fixture.category("AWS")
        fixture.quiz(category, fixture.difficulty(category, "SAA", 2), "問題 1")
    }

    // --- 認証 -----------------------------------------------------------------

    @Test
    @DisplayName("利用者を示さないと 401")
    fun anonymousIsUnauthorized() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/current").andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("利用者を示さなければ、テナントが無くても 401")
    fun anonymousCannotProbeTenants() {
        // 存在しない slug だけ 404 になると、認証なしでテナントの有無を探れてしまう
        mockMvc.get("/api/t/unknown/admin/categories").andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("トークンとして読めない値を渡しても 401")
    fun malformedTokenIsUnauthorized() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", "Bearer not-a-jwt")
        }.andExpect { status { isUnauthorized() } }
    }

    // --- 所属 -----------------------------------------------------------------

    @Test
    @DisplayName("所属していないテナントは、存在しないものとして 404")
    fun outsiderSeesNotFound() {
        // 403 を返すと、そのテナントが実在することが分かってしまう
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.OUTSIDER))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("指定されたテナントは存在しません") }
        }
    }

    @Test
    @DisplayName("管理者でも、所属していない別テナントは 404")
    fun adminCannotReachAnotherTenant() {
        mockMvc.get("/api/t/${otherTenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("存在しない slug も 404")
    fun unknownTenantIsNotFound() {
        mockMvc.get("/api/t/unknown/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNotFound() } }
    }

    // --- ロール ---------------------------------------------------------------

    @Test
    @DisplayName("一般ユーザーは管理 API を使えない")
    fun memberCannotUseAdminApi() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.MEMBER))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.title") { value("権限がありません") }
        }

        mockMvc.post("/api/t/${tenant.slug}/admin/categories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"勝手に作ったカテゴリ"}"""
            header("Authorization", TestAuth.bearer(TestAuth.MEMBER))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    @DisplayName("一般ユーザーでもクイズには回答できる")
    fun memberCanPlay() {
        mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("Authorization", TestAuth.bearer(TestAuth.MEMBER))
        }.andExpect { status { isCreated() } }
    }

    @Test
    @DisplayName("管理者は管理 API も出題 API も使える")
    fun adminCanUseBoth() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isCreated() } }
    }

    @Test
    @DisplayName("所属を外すと、それまで使えていた API が 404 になる")
    fun leavingTenantRevokesAccess() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }

        // 論理削除された所属は参照しない
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE core.tenant_members SET deleted_at = now() WHERE tenant_id = ? AND user_id = ?",
            tenant.id,
            TestAuth.ADMIN,
        )

        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNotFound() } }
    }
}
