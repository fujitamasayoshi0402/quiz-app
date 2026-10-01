package com.quizapp.notification

import java.util.UUID

/** テナントが設定した Slack の Webhook の URL を読む。**URL は secret として扱い、ログに出さない** */
fun interface WebhookUrls {
    /** 設定がなければ null */
    fun find(tenantId: UUID): String?
}

/**
 * 同じイベントを 2 度知らせないための記録（ADR-0022 の 4）。
 *
 * EventBridge と Lambda の非同期呼び出しは、同じイベントを 2 度以上届けることがある。
 * アーカイブから流し直したときも、同じ ID のイベントが届く
 */
interface Deliveries {
    /**
     * 「処理中」として書く。まだ無いか、処理中の期限が切れているときだけ書け、true を返す。
     * false なら、処理済みか、ほかの呼び出しが処理中
     */
    fun begin(eventId: UUID): Boolean

    /** 送れた、または送っても直らないと分かった。以後、同じイベントは捨てる */
    fun complete(eventId: UUID)

    /** 送れなかった。記録を消し、再試行で送り直せるようにする */
    fun release(eventId: UUID)
}

/** Slack の Incoming Webhook に送る。通信に失敗したら例外を投げる */
fun interface Slack {
    fun post(webhookUrl: String, payload: String): SlackResponse
}

/** Slack の応答。本文は失敗の理由（`no_service` など）で、URL は含まない */
data class SlackResponse(val status: Int, val body: String)

/**
 * Webhook の URL を確かめる。quiz-service が保存するときと同じ規則（`SlackWebhookUrl`）で、送る前にもう一度見る。
 * **任意の URL へ送ると、管理者が指定した先へ Lambda が要求を送る踏み台になる**（ADR-0022 の 5）
 */
object SlackWebhookUrl {
    private val PATTERN = Regex("^https://hooks\\.slack\\.com/[A-Za-z0-9/_-]+$")
    private const val MAX_LENGTH = 500

    fun isValid(url: String): Boolean = url.length <= MAX_LENGTH && PATTERN.matches(url)
}
