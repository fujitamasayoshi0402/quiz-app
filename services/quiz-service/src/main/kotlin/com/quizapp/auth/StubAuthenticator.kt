package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `X-User-Id` ヘッダをそのまま利用者とみなす。**Phase 3 で削除する。**
 *
 * 誰にでもなりすませるため、**本番で有効になると全テナントのデータが読める**。
 * 防御を 2 段構えにしている。
 *
 * 1. [Profile] で本番相当のプロファイルから除外する
 * 2. それでも有効なら [StubAuthenticatorGuard] が起動を失敗させる
 *
 * 1 だけだとプロファイル指定の誤りに気づけない。設定ミスが情報漏洩に直結する場所なので、
 * 「起動しない」ところまで倒しておく。
 */
@Component
@Profile("!prod & !stg")
class StubAuthenticator : Authenticator {

    override fun authenticate(request: HttpServletRequest): UUID? = request.getHeader(USER_ID_HEADER)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val USER_ID_HEADER = "X-User-Id"
    }
}
