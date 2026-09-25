package com.quizapp.auth

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestJwt
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

/**
 * アクセストークンの検証と、初めて見る利用者の扱い（ADR-0016）。
 *
 * 認証そのものは Cognito が行う。ここで確かめるのは、**受け取ったトークンを正しく拒否できるか**と、
 * 認証基盤の利用者をアプリの利用者に正しく対応付けられるか。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessTokenApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    private fun myTenants(token: String): ResultActionsDsl =
        mockMvc.get("/api/me/tenants") { header("Authorization", "Bearer $token") }

    private fun newSubject() = "sub-${UUID.randomUUID()}"

    private fun newEmail() = "user-${UUID.randomUUID()}@example.test"

    @Nested
    @DisplayName("受け付けないトークン")
    inner class Rejected {
        @Test
        @DisplayName("署名が改ざんされている")
        fun tamperedSignature() {
            val token = TestJwt.issue(newSubject())
            myTenants(token.dropLast(2) + "xx").andExpect { status { isUnauthorized() } }
        }

        @Test
        @DisplayName("期限が切れている")
        fun expired() {
            myTenants(TestJwt.issue(newSubject(), expiresAt = Instant.now().minusSeconds(120)))
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        @DisplayName("ID トークン。アクセストークンと同じ鍵で署名されているが、API には使わせない")
        fun idToken() {
            myTenants(TestJwt.issue(newSubject(), tokenUse = "id")).andExpect { status { isUnauthorized() } }
        }

        @Test
        @DisplayName("同じ User Pool の、別のクライアントに発行された")
        fun otherClient() {
            myTenants(TestJwt.issue(newSubject(), clientId = "other-client")).andExpect { status { isUnauthorized() } }
        }

        @Test
        @DisplayName("別の発行者")
        fun otherIssuer() {
            myTenants(TestJwt.issue(newSubject(), issuer = "https://issuer.test/other"))
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        @DisplayName("拒否したトークンでは、利用者を作らない")
        fun doesNotRegisterOnRejection() {
            val subject = newSubject()
            myTenants(TestJwt.issue(subject, tokenUse = "id")).andExpect { status { isUnauthorized() } }

            assertThat(userByExternalId(subject)).isNull()
        }
    }

    @Nested
    @DisplayName("初めて見る利用者")
    inner class Registration {
        @Test
        @DisplayName("利用者を作り、確認済みのメールアドレスを持つ。所属はまだない")
        fun registersNewUser() {
            val subject = newSubject()
            val email = newEmail()

            myTenants(TestJwt.issue(subject, email = email)).andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }

            assertThat(userByExternalId(subject)).isEqualTo(email to email.substringBefore('@'))
        }

        @Test
        @DisplayName("2 回目からは同じ利用者として扱う")
        fun reusesRegisteredUser() {
            val subject = newSubject()
            myTenants(TestJwt.issue(subject, email = newEmail())).andExpect { status { isOk() } }
            myTenants(TestJwt.issue(subject, email = newEmail())).andExpect { status { isOk() } }

            assertThat(countByExternalId(subject)).isEqualTo(1)
        }

        @Test
        @DisplayName("メールアドレスが確認されていなければ、アドレスを持たずに作る")
        fun unverifiedEmail() {
            val subject = newSubject()
            myTenants(TestJwt.issue(subject)).andExpect { status { isOk() } }

            assertThat(userByExternalId(subject)).isEqualTo(null to "利用者")
        }
    }

    @Nested
    @DisplayName("事前に登録した利用者")
    inner class PreRegistered {
        @Test
        @DisplayName("同じメールアドレスで初めてログインすると結び付き、所属を引き継ぐ")
        fun claimsByEmail() {
            val email = newEmail()
            val userId = preRegister(email)
            val tenant = TestTenant.create().join(userId, "admin")
            val subject = newSubject()

            // 大文字と小文字は区別しない
            myTenants(TestJwt.issue(subject, email = email.uppercase())).andExpect {
                status { isOk() }
                jsonPath("$[0].slug") { value(tenant.slug) }
                jsonPath("$[0].role") { value("admin") }
            }
        }

        @Test
        @DisplayName("ほかの利用者に結び付いたあとは、同じアドレスでも奪えない")
        fun cannotReclaim() {
            val email = newEmail()
            val userId = preRegister(email)
            TestTenant.create().join(userId, "admin")
            myTenants(TestJwt.issue(newSubject(), email = email)).andExpect { status { isOk() } }

            // 認証基盤は同じアドレスの利用者を 2 人作らないが、作り直した場合などに備える。
            // 別の利用者として作ると、所属と履歴が黙って分かれる
            val another = newSubject()
            myTenants(TestJwt.issue(another, email = email)).andExpect { status { isUnauthorized() } }
            assertThat(userByExternalId(another)).isNull()
        }

        @Test
        @DisplayName("確認されていないアドレスでは結び付かない")
        fun unverifiedCannotClaim() {
            val email = newEmail()
            val userId = preRegister(email)
            TestTenant.create().join(userId, "admin")

            myTenants(TestJwt.issue(newSubject())).andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
        }

        private fun preRegister(email: String): UUID {
            val id = UUID.randomUUID()
            TestPostgres.adminJdbcTemplate.update(
                "INSERT INTO core.users (id, external_id, email, display_name) VALUES (?, NULL, ?, ?)",
                id,
                email,
                "事前登録",
            )
            return id
        }
    }

    private fun userByExternalId(externalId: String): Pair<String?, String>? = TestPostgres.adminJdbcTemplate.query(
        "SELECT email, display_name FROM core.users WHERE external_id = ?",
        { rs, _ -> rs.getString("email") to rs.getString("display_name") },
        externalId,
    ).firstOrNull()

    private fun countByExternalId(externalId: String): Int? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.users WHERE external_id = ?",
        Int::class.java,
        externalId,
    )
}
