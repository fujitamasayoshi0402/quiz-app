package com.quizapp.logging

import org.slf4j.MDC
import java.util.UUID

/**
 * ログの各行に載せる、要求の文脈（MDC）。
 *
 * AWS では JSON（ECS 形式）で出し、Logs Insights で項目として絞り込める（開発ガイドライン「ログ」）。
 * 名前の `.` は JSON の入れ子になる（`tenant.id` は `{"tenant": {"id": ...}}`）。
 *
 * **載せるのは ID だけ。** メールアドレス、トークン、Webhook の URL は載せない。
 * ID だけでは人を特定できず、引くには DB に入る権限が要る。
 *
 * 入れるのは要求の入口のフィルタ。消すのは [RequestLogFilter] がまとめて行う。
 * テナントと利用者のフィルタは、自分の後始末で文脈（`TenantContext`、`UserContext`）を消すが、MDC は残す。
 * 要求の終わりに出す 1 行にも載せたいため
 */
object LogContext {
    const val REQUEST_ID = "http.request.id"
    const val TENANT_ID = "tenant.id"
    const val USER_ID = "user.id"

    private val keys = listOf(REQUEST_ID, TENANT_ID, USER_ID)

    fun putTenant(tenantId: UUID) = MDC.put(TENANT_ID, tenantId.toString())

    fun putUser(userId: UUID) = MDC.put(USER_ID, userId.toString())

    fun clear() = keys.forEach(MDC::remove)
}
