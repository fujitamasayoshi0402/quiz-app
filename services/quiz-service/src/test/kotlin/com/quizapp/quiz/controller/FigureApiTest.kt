package com.quizapp.quiz.controller

import com.quizapp.quiz.infrastructure.BucketFigureStore
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestImages
import com.quizapp.support.TestTenant
import com.quizapp.support.fake.InMemoryBucket
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

/**
 * 解説図の統合テスト（ADR-0017）。
 *
 * S3 はメモリ上の置き場所に差し替えている（TestFigureConfiguration）。キーの組み立てと URL の署名は本物を通す。
 * 署名付き URL はローカルと同じく S3 の形になる。CloudFront の署名は CloudFrontFigureUrlSignerTest が見る。
 *
 * 別テナントの図を指定したときは、TenantBoundaryApiTest が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FigureApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect/></svg>"""
        private const val SOURCE = """<mxfile><diagram name="1">図の原本</diagram></mxfile>"""
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var bucket: InMemoryBucket

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("図のテナント").withAdmin().join(TestAuth.MEMBER)
    }

    private fun create(user: UUID = TestAuth.ADMIN, svg: String = SVG, source: String = SOURCE): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/admin/figures") {
            header("Authorization", TestAuth.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("source" to source, "svg" to svg))
        }

    private fun createFigure(): UUID = create().andExpect { status { isCreated() } }
        .andReturn().response.contentAsString
        .let { UUID.fromString(objectMapper.readTree(it)["id"].asString()) }

    private fun play(id: UUID, user: UUID? = TestAuth.MEMBER): ResultActionsDsl =
        mockMvc.get("/api/t/${tenant.slug}/play/figures/$id") {
            user?.let { header("Authorization", TestAuth.bearer(it)) }
        }

    @Test
    @DisplayName("置いた図は、原本と SVG がテナントを含むキーに入り、SVG は変えない前提で長く持たせる")
    fun storesUnderTenant() {
        val id = createFigure()

        val svg = bucket.find("svg/${tenant.id}/$id.svg")
        assertThat(svg?.body?.decodeToString()).isEqualTo(SVG)
        assertThat(svg?.contentType).isEqualTo("image/svg+xml")
        assertThat(svg?.cacheControl).isEqualTo(BucketFigureStore.IMMUTABLE)

        val source = bucket.find("drawio/${tenant.id}/$id.drawio")
        assertThat(source?.body?.decodeToString()).isEqualTo(SOURCE)
        assertThat(source?.cacheControl).isEqualTo("no-store")
    }

    @Test
    @DisplayName("管理者は原本を取り出せる。描き直すときに draw.io へ読み込ませる")
    fun returnsSource() {
        val id = createFigure()

        mockMvc.get("/api/t/${tenant.slug}/admin/figures/$id/source") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect {
            status { isOk() }
            jsonPath("$.source") { value(SOURCE) }
        }
    }

    @Test
    @DisplayName("所属していれば、SVG の署名付き URL へ送られる。送り先の応答は利用者ごとなので共有させない")
    fun redirectsMemberToSignedUrl() {
        val id = createFigure()

        val response = play(id).andExpect {
            status { isFound() }
            header { string("Cache-Control", "max-age=60, private") }
        }.andReturn().response

        val location = URI.create(requireNotNull(response.getHeader("Location")))
        assertThat(location.path).endsWith("/svg/${tenant.id}/$id.svg")
        assertThat(location.rawQuery).contains("X-Amz-Signature=").contains("X-Amz-Expires=")
        // SVG をこの API のオリジンから返さない
        assertThat(response.contentAsString).isEmpty()
    }

    @Test
    @DisplayName("消した図は、原本も URL も返さず、本体も残さない")
    fun deletesFigure() {
        val id = createFigure()

        mockMvc.delete("/api/t/${tenant.slug}/admin/figures/$id") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        play(id).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("指定された図は存在しません") }
        }
        assertThat(bucket.find("svg/${tenant.id}/$id.svg")).isNull()
        assertThat(bucket.find("drawio/${tenant.id}/$id.drawio")).isNull()
    }

    @Test
    @DisplayName("SVG として読めないものは置かない。何も残さない")
    fun rejectsInvalidSvg() {
        val before = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT count(*) FROM quiz.figures WHERE tenant_id = ?",
            Int::class.java,
            tenant.id,
        )

        create(svg = """<html><script>alert(1)</script></html>""").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("SVG として読めません") }
        }

        val after = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT count(*) FROM quiz.figures WHERE tenant_id = ?",
            Int::class.java,
            tenant.id,
        )
        assertThat(after).isEqualTo(before)
    }

    @Test
    @DisplayName("図を置けるのは管理者だけ。ログインしていなければ URL も返さない")
    fun requiresRoles() {
        create(user = TestAuth.MEMBER).andExpect { status { isForbidden() } }

        val id = createFigure()
        play(id, user = null).andExpect { status { isUnauthorized() } }
        play(id, user = TestAuth.OUTSIDER).andExpect { status { isNotFound() } }
    }

    private fun startUpload(
        type: String = "image/png",
        size: Long = 100,
        user: UUID = TestAuth.ADMIN,
    ): ResultActionsDsl = mockMvc.post("/api/t/${tenant.slug}/admin/figures/uploads") {
        header("Authorization", TestAuth.bearer(user))
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("contentType" to type, "size" to size))
    }

    /** 画像を上げる準備をし、ブラウザの代わりに検査の前の置き場所へ本体を置く */
    private fun upload(bytes: ByteArray, type: String = "image/png"): UUID {
        val id = startUpload(type, bytes.size.toLong()).andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
            .let { UUID.fromString(objectMapper.readTree(it)["id"].asString()) }
        bucket.put("incoming/${tenant.id}/$id", bytes, type, "no-store")
        return id
    }

    private fun complete(id: UUID): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/admin/figures/uploads/$id/complete") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }

    private fun figureRows(): Int? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM quiz.figures WHERE tenant_id = ?",
        Int::class.java,
        tenant.id,
    )

    @Test
    @DisplayName("画像を上げる URL は、検査の前の置き場所を指し、種類と大きさを署名に含める")
    fun issuesUploadUrl() {
        val body = startUpload(size = 1234).andExpect {
            status { isCreated() }
            jsonPath("$.headers.Content-Type") { value("image/png") }
        }.andReturn().response.contentAsString.let(objectMapper::readTree)

        val url = URI.create(body["url"].asString())
        assertThat(url.path).endsWith("/incoming/${tenant.id}/${body["id"].asString()}")
        assertThat(url.rawQuery).contains("X-Amz-Signature=").contains("X-Amz-Expires=300")
        // 申告と違う種類や大きさの本体は、S3 が署名の不一致で拒む
        val signedHeaders = url.rawQuery.split("&").first { it.startsWith("X-Amz-SignedHeaders=") }
        assertThat(signedHeaders).contains("content-length").contains("content-type")
    }

    @Test
    @DisplayName("PNG と JPEG 以外や、10 MB を超えるものは、上げる URL を出さない")
    fun rejectsUnsupportedUploads() {
        startUpload(type = "image/gif").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("PNG か JPEG の画像を選んでください") }
        }
        startUpload(size = 10L * 1024 * 1024 + 1).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("画像は 10 MB までです") }
        }
        startUpload(size = 0).andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("上げた画像は、読み直してから配る場所に置き、検査の前の置き場所からは消す")
    fun completesUpload() {
        val id = upload(TestImages.pngWithText())

        complete(id).andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(id.toString()) }
        }

        val stored = requireNotNull(bucket.find("img/${tenant.id}/$id"))
        assertThat(stored.contentType).isEqualTo("image/png")
        assertThat(stored.cacheControl).isEqualTo(BucketFigureStore.IMMUTABLE)
        assertThat(String(stored.body, Charsets.ISO_8859_1)).doesNotContain(TestImages.LOCATION_MARKER)
        assertThat(bucket.find("incoming/${tenant.id}/$id")).isNull()

        mockMvc.get("/api/t/${tenant.slug}/admin/figures/$id") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { jsonPath("$.kind") { value("image") } }
        val location = play(id).andExpect { status { isFound() } }.andReturn().response.getHeader("Location")
        assertThat(URI.create(requireNotNull(location)).path).endsWith("/img/${tenant.id}/$id")
    }

    @Test
    @DisplayName("画像に原本はない。描き直すための原本を求められても返さない")
    fun imageHasNoSource() {
        val id = upload(TestImages.png())
        complete(id).andExpect { status { isCreated() } }

        mockMvc.get("/api/t/${tenant.slug}/admin/figures/$id/source") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("画像でないものは置かない。上げたものは消し、行も入れない")
    fun rejectsNonImageUpload() {
        val before = figureRows()
        val id = upload("<html><script>alert(1)</script></html>".toByteArray())

        complete(id).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("PNG か JPEG の画像を選んでください") }
        }

        assertThat(bucket.find("incoming/${tenant.id}/$id")).isNull()
        assertThat(bucket.find("img/${tenant.id}/$id")).isNull()
        assertThat(figureRows()).isEqualTo(before)
    }

    @Test
    @DisplayName("上げていない画像の完了は 404。2 回目の完了も同じ")
    fun completesOnlyUploaded() {
        complete(UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("上げた画像が見つかりません。もう一度上げてください") }
        }

        val id = upload(TestImages.png())
        complete(id).andExpect { status { isCreated() } }
        complete(id).andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("消した画像は、本体も残さない")
    fun deletesImage() {
        val id = upload(TestImages.jpeg())
        complete(id).andExpect { status { isCreated() } }

        mockMvc.delete("/api/t/${tenant.slug}/admin/figures/$id") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        assertThat(bucket.find("img/${tenant.id}/$id")).isNull()
    }

    @Test
    @DisplayName("draw.io の図の種類は drawio。画像を上げられるのも管理者だけ")
    fun reportsDrawioKindAndRequiresAdminToUpload() {
        val id = createFigure()

        mockMvc.get("/api/t/${tenant.slug}/admin/figures/$id") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("drawio") }
        }
        startUpload(user = TestAuth.MEMBER).andExpect { status { isForbidden() } }
    }

    @Test
    @DisplayName("存在しない図は 404")
    fun unknownFigure() {
        play(UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("指定された図は存在しません") }
        }
    }
}
