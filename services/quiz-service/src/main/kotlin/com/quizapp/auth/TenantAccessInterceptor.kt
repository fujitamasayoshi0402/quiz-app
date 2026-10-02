package com.quizapp.auth

import com.quizapp.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * テナント配下のエンドポイントへのアクセスを検査する。
 *
 * | パス | 必要な条件 |
 * | --- | --- |
 * | `/api/t/{slug}/admin/` 配下 | 管理者として所属していること |
 * | `/api/t/{slug}/` 配下のその他 | 所属していること |
 *
 * **既定が「所属が必要」**になっている。テナント配下にエンドポイントを足したとき、
 * 書き忘れても公開されない。個々のコントローラに注釈を付ける方式だと、
 * 付け忘れがそのまま穴になる。
 *
 * フィルタではなくインターセプタなのは、ここで投げた例外を
 * [com.quizapp.controller.ApiExceptionHandler] が受け取れるようにするため。
 * フィルタで投げると RFC 9457 の応答にならない。
 *
 * 認証の有無は先に [AuthenticationRequiredInterceptor] が確かめている。
 */
@Component
class TenantAccessInterceptor(private val memberships: TenantMemberships) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val tenantId = TenantContext.get()
            // slug が実在しない。存在しないテナントとして扱う
            ?: error("テナントを解決できませんでした: ${request.requestURI}")

        val userId = UserContext.require()
        val role = memberships.findRole(tenantId, userId) ?: throw TenantAccessDeniedException()

        if (isAdminRoute(request) && role != TenantRole.ADMIN) {
            throw AdminRoleRequiredException()
        }

        // 以降のロール判定でもう一度 DB を引かずに済むよう、解決した結果を載せる
        UserContext.set(CurrentUser(userId, role))
        return true
    }

    /**
     * 管理者用のルートか。**生の URI ではなく、振り分けた先のルートの型（`/api/t/{slug}/admin/...`）で決める**（DEV-114）。
     *
     * 生の URI には、エンコードした文字やパスの引数（`;` 以降）が残る。Spring はそれらを解いてからルートを選ぶため、
     * URI の文字列で判定すると、管理者用のルートに振り分けられる要求を、一般の要求と取り違えうる。
     * 判定と振り分けで、同じ解釈を使う。型が取れないときは、管理者用として扱う
     */
    private fun isAdminRoute(request: HttpServletRequest): Boolean {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: return true
        val segments = pattern.trim('/').split('/')
        val index = segments.indexOf("t")
        return index >= 0 && index + 2 < segments.size && segments[index + 2] == "admin"
    }
}
