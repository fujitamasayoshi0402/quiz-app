package com.quizapp.notification.support

import com.quizapp.notification.Deliveries
import com.quizapp.notification.Slack
import com.quizapp.notification.SlackResponse
import com.quizapp.notification.WebhookUrls
import java.util.UUID

class InMemoryWebhookUrls(private val urls: Map<UUID, String> = emptyMap()) : WebhookUrls {
    override fun find(tenantId: UUID): String? = urls[tenantId]
}

/** 処理中の期限は見ない。期限の扱いは DynamoDbDeliveriesTest が実際の DynamoDB（LocalStack）で見る */
class InMemoryDeliveries : Deliveries {
    val records = mutableMapOf<UUID, String>()

    override fun begin(eventId: UUID): Boolean = records.putIfAbsent(eventId, "PROCESSING") == null

    override fun complete(eventId: UUID) {
        records[eventId] = "DONE"
    }

    override fun release(eventId: UUID) {
        records.remove(eventId, "PROCESSING")
    }
}

/** 送ったものを残し、決めた応答を返す。[failure] があれば、送る代わりに投げる */
class RecordingSlack(
    private val response: SlackResponse = SlackResponse(200, "ok"),
    private val failure: Exception? = null,
) : Slack {
    val posted = mutableListOf<Pair<String, String>>()

    override fun post(webhookUrl: String, payload: String): SlackResponse {
        failure?.let { throw it }
        posted += webhookUrl to payload
        return response
    }
}
