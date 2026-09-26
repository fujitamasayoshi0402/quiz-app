package com.quizapp.invitation.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 招待を作り、受け入れる（ADR-0016）。
 *
 * 要点は 2 つ。
 * - **リンクを持っていても、招待されたメールアドレスでなければ入れない**
 * - 受け入れた人は、招待したロールで所属する。すでに所属していればロールは変えない
 *
 * 受け入れの API はテナントの外にあり、テナント境界のテスト（`TenantBoundaryApiTest`）の対象にならない。
 * **ここが境界の検証を兼ねる。**
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvitationApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val TENANT_NAME = "ゴルフ"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create(TENANT_NAME).withAdmin()
    }

    @Nested
    @DisplayName("作る")
    inner class Create {
        @Test
        @DisplayName("受け入れ待ちの招待ができ、トークンは作った応答にだけ含まれる")
        fun createsPendingInvitation() {
            val email = newEmail()
            val body = invite(email, "admin").andExpect { status { isCreated() } }.json()

            val invitation = body["invitation"]
            assertThat(invitation["email"].asString()).isEqualTo(email)
            assertThat(invitation["role"].asString()).isEqualTo("admin")
            assertThat(invitation["status"].asString()).isEqualTo("pending")
            assertThat(Duration.between(Instant.now(), Instant.parse(invitation["expiresAt"].asString())))
                .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7))

            val token = body["token"].asString()
            assertThat(openInvitations().map { it["id"].asString() }).contains(invitation["id"].asString())
            assertThat(listBody()).doesNotContain(token)
            assertThat(storedTokenHashes()).doesNotContain(token)
        }

        @Test
        @DisplayName("同じアドレスへ招待し直すと、前のリンクは使えなくなる")
        fun reinvitingRevokesPreviousLink() {
            val email = newEmail()
            val first = created(email)
            val second = created(email.uppercase())

            val user = TestAuth.createUser(email = email)
            assertThat(received(first.token, user).andExpect { status { isOk() } }.json()["status"].asString())
                .isEqualTo("revoked")
            assertThat(openInvitations().map { it["id"].asString() })
                .contains(second.id)
                .doesNotContain(first.id)
        }

        @Test
        @DisplayName("管理者でなければ招待できない")
        fun memberCannotInvite() {
            tenant.join(TestAuth.MEMBER)
            invite(newEmail(), "admin", actor = TestAuth.MEMBER).andExpect { status { isForbidden() } }
        }

        @Test
        @DisplayName("すでに所属している人は招待できない")
        fun existingMemberCannotBeInvited() {
            val email = newEmail()
            tenant.join(TestAuth.createUser(email = email))

            invite(email.uppercase()).andExpect {
                status { isConflict() }
                jsonPath("$.detail") { value("このメールアドレスの利用者は、すでにテナントに所属しています") }
            }
        }

        @Test
        @DisplayName("メールアドレスとロールの形を確かめる")
        fun rejectsInvalidInput() {
            invite("not-an-email").andExpect {
                status { isBadRequest() }
                jsonPath("$.errors.email") { exists() }
            }
            invite(newEmail(), "owner").andExpect {
                status { isBadRequest() }
                jsonPath("$.errors.role") { exists() }
            }
        }
    }

    @Nested
    @DisplayName("取り消す")
    inner class Revoke {
        @Test
        @DisplayName("取り消すと一覧から消え、リンクは使えなくなる")
        fun revokedLinkStopsWorking() {
            val email = newEmail()
            val invitation = created(email)

            revoke(invitation.id).andExpect { status { isNoContent() } }

            assertThat(openInvitations().map { it["id"].asString() }).doesNotContain(invitation.id)
            accept(invitation.token, TestAuth.createUser(email = email)).andExpect {
                status { isConflict() }
                jsonPath("$.invitationStatus") { value("revoked") }
            }
        }

        @Test
        @DisplayName("取り消し済みと受け入れ済みは取り消せない")
        fun closedCannotBeRevoked() {
            val revoked = created(newEmail())
            revoke(revoked.id).andExpect { status { isNoContent() } }
            revoke(revoked.id).andExpect { status { isNotFound() } }

            val email = newEmail()
            val accepted = created(email)
            accept(accepted.token, TestAuth.createUser(email = email)).andExpect { status { isOk() } }
            revoke(accepted.id).andExpect { status { isNotFound() } }
        }
    }

    @Nested
    @DisplayName("受け取る")
    inner class Receive {
        @Test
        @DisplayName("招待先のテナントとロールが分かる。テナントの slug は返さない")
        fun showsTenantAndRole() {
            val email = newEmail()
            val invitation = created(email, "admin")

            val body = received(invitation.token, TestAuth.createUser(email = email.uppercase()))
                .andExpect { status { isOk() } }
                .andReturn().response.contentAsString

            val json = objectMapper.readTree(body)
            assertThat(json["tenantName"].asString()).isEqualTo(TENANT_NAME)
            assertThat(json["role"].asString()).isEqualTo("admin")
            assertThat(json["status"].asString()).isEqualTo("pending")
            assertThat(body).doesNotContain(tenant.slug)
        }

        @Test
        @DisplayName("別のアドレスでログインしていると 403。招待先も宛先も明かさない")
        fun otherAddressIsForbidden() {
            val email = newEmail()
            val invitation = created(email)

            val body = received(invitation.token, TestAuth.createUser(email = newEmail()))
                .andExpect { status { isForbidden() } }
                .andReturn().response.contentAsString

            assertThat(body).doesNotContain(TENANT_NAME, tenant.slug, email)
        }

        @Test
        @DisplayName("確認済みのアドレスを持たない利用者は、どの招待も開けない")
        fun userWithoutEmailIsForbidden() {
            received(created(newEmail()).token, TestAuth.createUser()).andExpect { status { isForbidden() } }
        }

        @Test
        @DisplayName("知らないトークンは 404")
        fun unknownToken() {
            received("unknown-token", TestAuth.createUser(email = newEmail())).andExpect { status { isNotFound() } }
        }

        @Test
        @DisplayName("テナントが削除されていたら 404")
        fun deletedTenant() {
            val email = newEmail()
            val invitation = created(email)
            TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET deleted_at = now() WHERE id = ?", tenant.id)

            received(invitation.token, TestAuth.createUser(email = email)).andExpect { status { isNotFound() } }
        }

        @Test
        @DisplayName("利用者を示さないと 401")
        fun anonymousIsUnauthorized() {
            val token = created(newEmail()).token
            mockMvc.get("/api/me/invitations/$token").andExpect { status { isUnauthorized() } }
            mockMvc.post("/api/me/invitations/$token/accept").andExpect { status { isUnauthorized() } }
        }
    }

    @Nested
    @DisplayName("受け入れる")
    inner class Accept {
        @Test
        @DisplayName("招待したロールで所属し、所属の一覧に出る")
        fun joinsWithInvitedRole() {
            val email = newEmail()
            val invitation = created(email, "admin")
            val user = TestAuth.createUser(email = email)

            accept(invitation.token, user).andExpect {
                status { isOk() }
                jsonPath("$.slug") { value(tenant.slug) }
                jsonPath("$.name") { value(TENANT_NAME) }
                jsonPath("$.role") { value("admin") }
            }

            assertThat(roleOf(user)).isEqualTo("admin")
            assertThat(openInvitations().map { it["id"].asString() }).doesNotContain(invitation.id)
            mockMvc.get("/api/me/tenants") { header("Authorization", TestAuth.bearer(user)) }
                .andExpect { jsonPath("$[0].slug") { value(tenant.slug) } }
        }

        @Test
        @DisplayName("同じ人がもう一度受け入れても失敗しない")
        fun acceptingTwiceIsHarmless() {
            val email = newEmail()
            val invitation = created(email)
            val user = TestAuth.createUser(email = email)

            accept(invitation.token, user).andExpect { status { isOk() } }
            accept(invitation.token, user).andExpect {
                status { isOk() }
                jsonPath("$.slug") { value(tenant.slug) }
            }
        }

        @Test
        @DisplayName("招待のあとに別の経路で所属していたら、ロールは変えない")
        fun keepsExistingRole() {
            val email = newEmail()
            val invitation = created(email, "admin")
            val user = TestAuth.createUser(email = email)
            tenant.join(user, "member")

            accept(invitation.token, user).andExpect {
                status { isOk() }
                jsonPath("$.role") { value("member") }
            }
            assertThat(roleOf(user)).isEqualTo("member")
        }

        @Test
        @DisplayName("別のアドレスでは受け入れられず、所属もしない")
        fun otherAddressCannotAccept() {
            val invitation = created(newEmail())
            val stranger = TestAuth.createUser(email = newEmail())

            accept(invitation.token, stranger).andExpect { status { isForbidden() } }
            assertThat(roleOf(stranger)).isNull()
        }

        @Test
        @DisplayName("期限が切れていたら受け入れられない")
        fun expired() {
            val email = newEmail()
            val invitation = created(email)
            TestPostgres.adminJdbcTemplate.update(
                "UPDATE core.invitations SET expires_at = now() - interval '1 second' WHERE id = ?",
                UUID.fromString(invitation.id),
            )
            val user = TestAuth.createUser(email = email)

            accept(invitation.token, user).andExpect {
                status { isConflict() }
                jsonPath("$.invitationStatus") { value("expired") }
            }
            assertThat(roleOf(user)).isNull()
        }

        @Test
        @DisplayName("受け入れたあとに所属を外れたら、同じ招待では戻れない")
        fun cannotRejoinWithUsedInvitation() {
            val email = newEmail()
            val invitation = created(email)
            val user = TestAuth.createUser(email = email)
            accept(invitation.token, user).andExpect { status { isOk() } }
            TestPostgres.adminJdbcTemplate.update(
                "UPDATE core.tenant_members SET deleted_at = now() WHERE tenant_id = ? AND user_id = ?",
                tenant.id,
                user,
            )

            accept(invitation.token, user).andExpect {
                status { isConflict() }
                jsonPath("$.invitationStatus") { value("accepted") }
            }
        }
    }

    // --- 補助 -----------------------------------------------------------------

    private data class Created(val id: String, val token: String)

    private fun newEmail() = "invitee-${UUID.randomUUID()}@example.test"

    private fun invite(email: String, role: String = "member", actor: UUID = TestAuth.ADMIN): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/admin/invitations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","role":"$role"}"""
            header("Authorization", TestAuth.bearer(actor))
        }

    private fun created(email: String, role: String = "member"): Created =
        invite(email, role).andExpect { status { isCreated() } }.json()
            .let { Created(it["invitation"]["id"].asString(), it["token"].asString()) }

    private fun revoke(id: String): ResultActionsDsl = mockMvc.delete("/api/t/${tenant.slug}/admin/invitations/$id") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }

    private fun received(token: String, user: UUID): ResultActionsDsl =
        mockMvc.get("/api/me/invitations/$token") { header("Authorization", TestAuth.bearer(user)) }

    private fun accept(token: String, user: UUID): ResultActionsDsl =
        mockMvc.post("/api/me/invitations/$token/accept") { header("Authorization", TestAuth.bearer(user)) }

    private fun listBody(): String = mockMvc.get("/api/t/${tenant.slug}/admin/invitations") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun openInvitations(): List<JsonNode> = objectMapper.readTree(listBody()).values().toList()

    private fun storedTokenHashes(): List<String> = TestPostgres.adminJdbcTemplate.queryForList(
        "SELECT token_hash FROM core.invitations WHERE tenant_id = ?",
        String::class.java,
        tenant.id,
    ).filterNotNull()

    private fun roleOf(user: UUID): String? = TestPostgres.adminJdbcTemplate.queryForList(
        "SELECT role FROM core.tenant_members WHERE tenant_id = ? AND user_id = ? AND deleted_at IS NULL",
        String::class.java,
        tenant.id,
        user,
    ).firstOrNull()

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
