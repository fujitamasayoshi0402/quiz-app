package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/**
 * リクエストから利用者を特定する。**認証方式をここ 1 つに閉じ込める。**
 *
 * いまは [CognitoAuthenticator]（Cognito のアクセストークン）。自前のパスキーの実装に差し替えるなら（ADR-0027）、
 * 作り直すのはこの実装だけ。
 * **ロールと所属は DB（`core.tenant_members`）から引く**ため、認可の仕組みは認証方式に依存しない。
 */
interface Authenticator {

    /** 特定できなければ null を返す。認証が要るかどうかの判断は呼び出し側が持つ。 */
    fun authenticate(request: HttpServletRequest): UUID?
}
