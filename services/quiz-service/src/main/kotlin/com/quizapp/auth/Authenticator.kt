package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/**
 * リクエストから利用者を特定する。**認証方式をここ 1 つに閉じ込める。**
 *
 * Phase 1 は [StubAuthenticator]（`X-User-Id` ヘッダ）、Phase 3 で Cognito の JWT 検証に差し替える。
 *
 * ### Phase 3 で触るファイル
 *
 * - `auth/StubAuthenticator.kt` … 削除する
 * - `auth/CognitoAuthenticator.kt` … 新規に追加する（JWT の検証と `sub` から利用者の解決）
 * - `auth/StubAuthenticatorGuard.kt` … スタブが無くなるので削除する
 *
 * これ以外は変わらない。**ロールと所属はどちらの方式でも DB（`core.tenant_members`）から引く**ため、
 * 認可の仕組みは認証方式に依存しない。
 */
interface Authenticator {

    /** 特定できなければ null を返す。認証が要るかどうかの判断は呼び出し側が持つ。 */
    fun authenticate(request: HttpServletRequest): UUID?
}
