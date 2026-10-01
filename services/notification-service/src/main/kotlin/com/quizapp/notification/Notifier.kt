package com.quizapp.notification

import com.quizapp.events.QuizEvent

/**
 * 1 件のイベントを、そのテナントの Slack に知らせる（ADR-0022 の 4〜6）。
 *
 * 1. テナントの Webhook の URL を読む。**無ければ何もせずに終わる**
 * 2. 重複を捨てる。処理済みか処理中なら何もしない
 * 3. Slack に送る
 *    - 届いた → 済みにする
 *    - 4xx（Webhook が消された、など）→ 再試行しても直らないため、ログに残して済みにする
 *    - 429・5xx・通信の失敗 → 記録を消して例外で終わる。Lambda の非同期呼び出しが再試行し、尽きたら DLQ に入る
 *
 * 3 で届いてから済みにするまでの間に落ちると、期限が切れたあとの再試行で、同じ通知がもう一度届く。まれなので受け入れる
 */
class Notifier(
    private val webhookUrls: WebhookUrls,
    private val deliveries: Deliveries,
    private val slack: Slack,
    private val messages: SlackMessages,
    private val log: (String) -> Unit,
) {
    fun notify(event: QuizEvent) {
        val subject = "eventId=${event.eventId} tenantId=${event.tenant.id}"
        val url = webhookUrls.find(event.tenant.id)
        when {
            url == null -> log("通知先が設定されていないため、知らせません: $subject")

            // 保存するときに quiz-service が確かめている。ここに来るのは、パラメータを直接書き換えたときだけ
            !SlackWebhookUrl.isValid(url) -> log("通知先の URL が Slack の Webhook ではないため、送りません: $subject")

            !deliveries.begin(event.eventId) -> log("処理済みか処理中のイベントのため、知らせません: $subject")

            else -> deliver(event, url, subject)
        }
    }

    private fun deliver(event: QuizEvent, url: String, subject: String) {
        val response = post(event, url, subject)
        when {
            response.status in SUCCESS -> {
                deliveries.complete(event.eventId)
                log("知らせました: $subject")
            }

            response.status == TOO_MANY_REQUESTS || response.status >= SERVER_ERROR -> {
                deliveries.release(event.eventId)
                throw SlackUnavailableException("Slack が ${response.status} を返しました: $subject ${response.body}")
            }

            else -> {
                deliveries.complete(event.eventId)
                log("Slack が ${response.status} を返したため、送るのをやめました: $subject ${response.body}")
            }
        }
    }

    /**
     * どの例外でも、記録を消してから投げ直す。消さずに終わると、処理中の期限が切れるまでの再試行が、処理中として捨てられる。
     * 元の例外はつながない。通信の例外の文に URL が入ると、Lambda が失敗として書くログに secret が残る
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun post(event: QuizEvent, url: String, subject: String): SlackResponse = try {
        slack.post(url, messages.of(event))
    } catch (e: Exception) {
        deliveries.release(event.eventId)
        throw SlackUnavailableException("Slack に送れませんでした: $subject ${e.javaClass.name}")
    }

    private companion object {
        val SUCCESS = 200..299
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
    }
}

/** 再試行すれば届くかもしれない失敗。Lambda の非同期呼び出しに再試行させる */
class SlackUnavailableException(message: String) : RuntimeException(message)
