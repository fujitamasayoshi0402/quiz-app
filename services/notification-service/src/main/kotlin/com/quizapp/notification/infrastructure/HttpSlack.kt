package com.quizapp.notification.infrastructure

import com.quizapp.notification.Slack
import com.quizapp.notification.SlackResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Slack の Incoming Webhook に POST する。JDK の HTTP クライアントを使い、依存を増やさない */
class HttpSlack(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        // Slack の Webhook はリダイレクトしない。踏み台にされないよう、別の先へは行かない
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : Slack {
    override fun post(webhookUrl: String, payload: String): SlackResponse {
        val request = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return SlackResponse(response.statusCode(), response.body().take(MAX_BODY_LENGTH))
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Lambda の時間切れ（Terraform の timeout、30 秒）より短くする。待っている間に落ちると、記録を消せない */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** 失敗の理由（`no_service` など）を読めれば足りる。ログに長い本文を残さない */
        const val MAX_BODY_LENGTH = 200
    }
}

/**
 * Slack へは送らず、送る内容をログに出す。ローカル（LocalStack）で動かすときに使う（`SLACK_DELIVERY=log`）。
 * URL は出さない
 */
class LoggingSlack(private val log: (String) -> Unit) : Slack {
    private companion object {
        const val OK = 200
    }

    override fun post(webhookUrl: String, payload: String): SlackResponse {
        log("Slack へは送らず、内容だけを出します: $payload")
        return SlackResponse(OK, "ok")
    }
}
