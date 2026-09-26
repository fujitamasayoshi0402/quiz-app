package com.quizapp.invitation.domain

import com.quizapp.auth.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 招待の状態と、宛先の照合。
 *
 * **受け入れと取り消しは、期限より優先する。** 期限が過ぎても、使ったことや取り消したことは変わらない。
 * 期限切れとして返すと、使い終わった招待に「作り直してもらってください」と案内してしまう。
 */
class InvitationTest {

    private val now: Instant = Instant.parse("2026-01-10T00:00:00Z")

    private fun invitation(
        email: String = "invitee@example.test",
        expiresAt: Instant = now.plusSeconds(60),
        acceptedAt: Instant? = null,
        revokedAt: Instant? = null,
    ) = Invitation(
        id = UUID.randomUUID(),
        tenantId = UUID.randomUUID(),
        email = email,
        role = TenantRole.MEMBER,
        expiresAt = expiresAt,
        createdAt = now.minusSeconds(60),
        acceptedAt = acceptedAt,
        acceptedBy = acceptedAt?.let { UUID.randomUUID() },
        revokedAt = revokedAt,
    )

    @Nested
    @DisplayName("状態")
    inner class Status {
        @Test
        @DisplayName("期限の前で、使っても取り消してもいなければ受け入れ待ち")
        fun pending() {
            assertThat(invitation().statusAt(now)).isEqualTo(InvitationStatus.PENDING)
        }

        @Test
        @DisplayName("期限の時刻ちょうどで期限切れ")
        fun expiresAtTheInstant() {
            assertThat(invitation(expiresAt = now).statusAt(now)).isEqualTo(InvitationStatus.EXPIRED)
        }

        @Test
        @DisplayName("使ったあとに期限が過ぎても、使用済みのまま")
        fun acceptedOutlivesExpiry() {
            val accepted = invitation(expiresAt = now.minusSeconds(1), acceptedAt = now.minusSeconds(30))
            assertThat(accepted.statusAt(now)).isEqualTo(InvitationStatus.ACCEPTED)
        }

        @Test
        @DisplayName("取り消したあとに期限が過ぎても、取り消しのまま")
        fun revokedOutlivesExpiry() {
            val revoked = invitation(expiresAt = now.minusSeconds(1), revokedAt = now.minusSeconds(30))
            assertThat(revoked.statusAt(now)).isEqualTo(InvitationStatus.REVOKED)
        }
    }

    @Nested
    @DisplayName("宛先")
    inner class Addressee {
        @Test
        @DisplayName("大文字と小文字を区別しない")
        fun caseInsensitive() {
            assertThat(invitation(email = "Invitee@Example.test").isAddressedTo("invitee@example.TEST")).isTrue()
        }

        @Test
        @DisplayName("別のアドレスには一致しない")
        fun otherAddress() {
            assertThat(invitation().isAddressedTo("someone@example.test")).isFalse()
        }

        @Test
        @DisplayName("確認済みのアドレスが無ければ一致しない")
        fun noVerifiedAddress() {
            assertThat(invitation().isAddressedTo(null)).isFalse()
        }
    }

    @Nested
    @DisplayName("トークン")
    inner class Token {
        @Test
        @DisplayName("URL にそのまま入る 256 ビットの乱数で、毎回違う")
        fun urlSafeAndUnique() {
            val tokens = (1..100).map { InvitationTokens.generate() }
            assertThat(tokens).allSatisfy { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
            assertThat(tokens.toSet()).hasSize(tokens.size)
        }

        @Test
        @DisplayName("ハッシュは同じトークンから同じ値になり、トークンそのものを含まない")
        fun hashIsStable() {
            val token = InvitationTokens.generate()
            assertThat(InvitationTokens.hash(token))
                .isEqualTo(InvitationTokens.hash(token))
                .matches("[0-9a-f]{64}")
                .doesNotContain(token)
        }
    }
}
