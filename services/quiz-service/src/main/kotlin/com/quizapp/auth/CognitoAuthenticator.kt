package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Cognito のアクセストークン（`Authorization: Bearer`）から利用者を特定する（ADR-0016）。
 *
 * トークンを付けるのは web の proxy。ブラウザにはトークンを渡さない。
 *
 * **初めて見る利用者は、ここで作る。** 認証基盤の `sub` をアプリの利用者に対応付け、以降はアプリの ID で扱う。
 * 事前に登録した利用者（メールアドレスだけを持つ）がいれば、そちらに結び付ける。
 */
@Component
class CognitoAuthenticator(
    private val decoder: JwtDecoder,
    private val accounts: UserAccounts,
    private val profiles: UserProfiles,
) : Authenticator {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun authenticate(request: HttpServletRequest): UUID? {
        val token = bearerToken(request) ?: return null
        return subjectOf(token)?.let { subject -> accounts.findIdByExternalId(subject) ?: register(subject, token) }
    }

    private fun bearerToken(request: HttpServletRequest): String? = request.getHeader(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
        ?.substring(BEARER.length)
        ?.trim()
        ?.ifEmpty { null }

    private fun subjectOf(token: String): String? = try {
        decoder.decode(token).subject
    } catch (e: JwtException) {
        // 期限切れはふつうに起きる（web の proxy が更新する前に届いたなど）。拒否するだけで、警告にはしない
        log.debug("アクセストークンを受け付けませんでした: {}", e.message)
        null
    }

    private fun register(subject: String, token: String): UUID? {
        val email = profiles.fetch(token).email
        val claimed = email?.let { accounts.claimByEmail(subject, it) }
        return when {
            claimed != null -> claimed.also { log.info("事前に登録した利用者に結び付けました: {}", it) }

            // 認証基盤で利用者を作り直したなど。別の利用者として作ると、所属と履歴が黙って分かれる。
            // 結び直すかどうかは人が判断する（開発ガイドライン「認証（Cognito）」）
            email != null && accounts.existsByEmail(email) -> null.also {
                log.warn("同じメールアドレスの利用者が、別の認証基盤の ID に結び付いています。受け付けません: {}", subject)
            }

            else -> accounts.create(subject, email, displayNameOf(email)).also { log.info("利用者を作りました: {}", it) }
        }
    }

    /** 表示名は後から変えられるようにする。いまはメールアドレスの @ より前を使う */
    private fun displayNameOf(email: String?): String =
        email?.substringBefore('@')?.take(DISPLAY_NAME_MAX_LENGTH)?.ifBlank { null } ?: DEFAULT_DISPLAY_NAME

    private companion object {
        const val BEARER = "Bearer "
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val DEFAULT_DISPLAY_NAME = "利用者"
    }
}
