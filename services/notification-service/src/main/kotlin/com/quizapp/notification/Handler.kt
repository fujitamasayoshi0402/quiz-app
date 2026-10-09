package com.quizapp.notification

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.quizapp.notification.infrastructure.DynamoDbDeliveries
import com.quizapp.notification.infrastructure.HttpSlack
import com.quizapp.notification.infrastructure.LoggingSlack
import com.quizapp.notification.infrastructure.SsmWebhookUrls
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.ssm.SsmClient
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

/**
 * Lambda の入口（`com.quizapp.notification.Handler::handleRequest`）。EventBridge のルールから非同期で呼ばれる（ADR-0022）。
 *
 * 環境変数（Terraform の modules/notification-service が渡す）:
 *
 * | 名前                       | 中身                                                   |
 * | -------------------------- | ------------------------------------------------------ |
 * | `WEBHOOK_PARAMETER_PREFIX` | テナントの Webhook の URL を置く SSM のパラメータの頭  |
 * | `DELIVERIES_TABLE`         | 重複を捨てる記録の DynamoDB の表                       |
 * | `WEB_BASE_URL`             | 管理画面へのリンクに使う画面のオリジン                 |
 * | `SLACK_DELIVERY`           | `send`（既定）/ `log`（送らずに内容をログに出す。ローカル用） |
 *
 * AWS のクライアントは、呼ばれるたびではなく初期化のときに 1 度だけ作る。同じ実行環境が続けて呼ばれたときに使い回す。
 * 接続先は SDK の既定（環境変数 `AWS_ENDPOINT_URL` があればそれ）に従う。LocalStack は Lambda にこの変数を渡す
 */
class Handler : RequestStreamHandler {
    private val webhookUrls = SsmWebhookUrls(SsmClient.create(), env("WEBHOOK_PARAMETER_PREFIX"))
    private val deliveries = DynamoDbDeliveries(DynamoDbClient.create(), env("DELIVERIES_TABLE"), Clock.systemUTC())
    private val messages = SlackMessages(env("WEB_BASE_URL").trimEnd('/'))
    private val sendsToSlack = (System.getenv("SLACK_DELIVERY") ?: "send") == "send"
    private val httpSlack = HttpSlack()

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val log: (String) -> Unit = { context.logger.log(it + "\n") }
        val event = QuizEventReader.read(input)
        if (event == null) {
            log("知らない種類のイベントのため、何もしません")
            return
        }
        val slack = if (sendsToSlack) httpSlack else LoggingSlack(log)
        Notifier(webhookUrls, deliveries, slack, messages, log).notify(event)
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("環境変数 $name がありません")
}
