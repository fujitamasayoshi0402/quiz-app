package com.quizapp.auth

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

/**
 * 見学者（ADR-0035）が、管理の API で何を読めて、何ができないか。
 *
 * **どの管理の GET を見学者に見せるかは、ここで一覧にして決める。** 見せるものには [VisibleToViewers] を付け、
 * 見せないものは [HIDDEN_FROM_VIEWERS] に理由とともに載せる。どちらでもない GET があると落ちる。
 * 付け忘れたときは見学者に 403 が返るだけで漏れないが、見せるかどうかを決めないまま足されないようにする。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ViewerAccessApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val ADMIN_PREFIX = "/api/t/{slug}/admin/"

        /** 見学者に見せない管理の GET と、その理由 */
        private val HIDDEN_FROM_VIEWERS = mapOf(
            "GET /api/t/{slug}/admin/invitations" to
                "招待した人のメールアドレスが載る。共有のアカウントで見に来た人に、ほかの人のアドレスを見せない",
        )
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    private lateinit var tenant: TestTenant
    private lateinit var viewer: UUID
    private lateinit var category: UUID
    private lateinit var quiz: UUID

    @BeforeEach
    fun setUp() {
        viewer = TestAuth.createUser("見学者")
        tenant = TestTenant.create().withAdmin().join(viewer, "viewer")

        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        quiz = fixture.quiz(category, fixture.difficulty(category, "SAA", 2), "問題 1")
    }

    // --- 網羅性 ---------------------------------------------------------------

    @Test
    @DisplayName("見学者に見せる印は、管理の GET にだけ付いている")
    fun annotationIsOnlyOnAdminReads() {
        val annotated = handlerMapping.handlerMethods
            .filter { (_, handler) -> handler.hasMethodAnnotation(VisibleToViewers::class.java) }
            .flatMap { (info, _) -> info.methodsCondition.methods.flatMap { m -> info.patternValues.map { m to it } } }

        assertThat(annotated).isNotEmpty
        assertThat(annotated)
            .describedAs("変更の API や、管理の外の API に付いている")
            .allSatisfy { (method, pattern) ->
                assertThat(method).isEqualTo(RequestMethod.GET)
                assertThat(pattern).startsWith(ADMIN_PREFIX)
            }
    }

    @Test
    @DisplayName("管理の GET はすべて、見学者に見せるかどうかが決まっている")
    fun everyAdminReadIsDecided() {
        val undecided = handlerMapping.handlerMethods
            .filter { (info, handler) ->
                RequestMethod.GET in info.methodsCondition.methods &&
                    info.patternValues.any { it.startsWith(ADMIN_PREFIX) } &&
                    !handler.hasMethodAnnotation(VisibleToViewers::class.java)
            }
            .flatMap { (info, _) -> info.patternValues.map { "GET $it" } }
            .toSet()

        assertThat(undecided)
            .describedAs("見せるなら @VisibleToViewers を付け、見せないなら HIDDEN_FROM_VIEWERS に理由を書く")
            .isEqualTo(HIDDEN_FROM_VIEWERS.keys)
    }

    // --- 読む -----------------------------------------------------------------

    @Test
    @DisplayName("見学者は管理の一覧と詳細を読める。正解も含む")
    fun viewerCanRead() {
        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(viewer))
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("AWS") }
        }

        // デモのクイズの正解が見えることは受け入れている（ADR-0035）
        mockMvc.get("/api/t/${tenant.slug}/admin/quizzes/$quiz") {
            header("Authorization", TestAuth.bearer(viewer))
        }.andExpect {
            status { isOk() }
            jsonPath("$.choices[0].isCorrect") { value(true) }
        }
    }

    @Test
    @DisplayName("見学者に見せない画面は 403 で、見学では見られないと伝える")
    fun viewerCannotReadHiddenScreen() {
        mockMvc.get("/api/t/${tenant.slug}/admin/invitations") {
            header("Authorization", TestAuth.bearer(viewer))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.title") { value("見学では見られません") }
        }
    }

    @Test
    @DisplayName("所属の一覧で、見学者のロールが返る")
    fun myTenantsShowViewerRole() {
        mockMvc.get("/api/me/tenants") {
            header("Authorization", TestAuth.bearer(viewer))
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].role") { value("viewer") }
        }
    }

    // --- 変える ---------------------------------------------------------------

    @Test
    @DisplayName("見学者は管理の API で何も変えられない。作る・直す・消すのどれも 403 で、何も変わらない")
    fun viewerCannotChange() {
        val bearer = TestAuth.bearer(viewer)
        listOf(
            mockMvc.post("/api/t/${tenant.slug}/admin/categories") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"見学者が作ったカテゴリ"}"""
                header("Authorization", bearer)
            },
            mockMvc.put("/api/t/${tenant.slug}/admin/categories/$category") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"見学者が直したカテゴリ"}"""
                header("Authorization", bearer)
            },
            mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$quiz") {
                header("Authorization", bearer)
            },
        ).forEach { result ->
            result.andExpect {
                status { isForbidden() }
                jsonPath("$.title") { value("見学では変更できません") }
            }
        }

        mockMvc.get("/api/t/${tenant.slug}/admin/categories") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("AWS") }
            jsonPath("$[0].quizCount") { value(1) }
        }
    }

    @Test
    @DisplayName("パスの書き方を変えても、見学者は管理の API で変えられない")
    fun viewerCannotChangeWithAlteredPath() {
        // 判定がルートと別の解釈でパスを読むと、管理の API ではないとみなして通してしまう（DEV-114）
        listOf(
            "/api/t/${tenant.slug}/%61dmin/categories",
            "/api/t/${tenant.slug}/admin;x=1/categories",
        ).forEach { path ->
            mockMvc.post(URI(path)) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"見学者が作ったカテゴリ"}"""
                header("Authorization", TestAuth.bearer(viewer))
            }.andExpect { status { isForbidden() } }
        }
    }

    // --- 解く -----------------------------------------------------------------

    @Test
    @DisplayName("見学者もクイズを解ける")
    fun viewerCanPlay() {
        mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("Authorization", TestAuth.bearer(viewer))
        }.andExpect { status { isCreated() } }
    }
}
