package com.quizapp.notification.domain

import java.time.Instant
import java.util.UUID

/** テナントが Slack の通知先を設定した、という印。**URL は持たない** */
data class SlackWebhookSetting(val configuredAt: Instant)

/**
 * 通知先を設定したかどうか（`quiz.slack_webhooks`）。
 *
 * URL の置き場所（[SlackWebhookStore]）は読めないため、設定したかどうかは DB の印で答える。
 */
interface SlackWebhookSettings {
    fun find(tenantId: UUID): SlackWebhookSetting?

    /** 印を付ける。すでにあれば、日時を今に替える */
    fun save(tenantId: UUID): SlackWebhookSetting

    fun delete(tenantId: UUID)
}

/**
 * URL の置き場所（SSM Parameter Store）。**書くことと消すことだけができ、読めない**（ADR-0022）。
 *
 * 読むのは notification-service だけ。quiz-service が読めると、画面や API から URL を取り出す道ができる。
 */
interface SlackWebhookStore {
    /** 置く。すでにあれば置き換える */
    fun put(tenantId: UUID, url: SlackWebhookUrl)

    /** 消す。無くても失敗にしない */
    fun delete(tenantId: UUID)
}

/** URL の置き場所に届かなかった。画面にはやり直してもらう */
class SlackWebhookStoreUnavailableException(cause: Throwable? = null) :
    RuntimeException("Slack の通知先の置き場所に届きません", cause)
