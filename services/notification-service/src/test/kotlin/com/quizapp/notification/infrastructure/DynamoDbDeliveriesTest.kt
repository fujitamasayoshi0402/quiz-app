package com.quizapp.notification.infrastructure

import com.quizapp.notification.support.TestLocalStack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 条件付きの書き込みは、実際の DynamoDB（LocalStack）で確かめる。フェイクでは条件式の誤りが分からない */
class DynamoDbDeliveriesTest {

    private val dynamo = TestLocalStack.client(DynamoDbClient.builder())
    private val table = "deliveries-" + UUID.randomUUID().toString().take(8)
    private val now = Instant.parse("2026-10-01T09:00:00Z")

    init {
        // Terraform（modules/notification-service）の表と同じキー
        dynamo.createTable {
            it.tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions({ a -> a.attributeName("eventId").attributeType(ScalarAttributeType.S) })
                .keySchema({ k -> k.attributeName("eventId").keyType(KeyType.HASH) })
        }
    }

    private fun deliveries(at: Instant = now) = DynamoDbDeliveries(dynamo, table, Clock.fixed(at, ZoneOffset.UTC))

    private fun record(eventId: UUID): Map<String, AttributeValue> =
        dynamo.getItem { it.tableName(table).key(mapOf("eventId" to AttributeValue.fromS(eventId.toString()))) }.item()

    @Test
    @DisplayName("初めてのイベントは処理中として書け、TTL は 7 日後")
    fun beginsNewEvent() {
        val eventId = UUID.randomUUID()

        assertThat(deliveries().begin(eventId)).isTrue()

        val record = record(eventId)
        assertThat(record["status"]?.s()).isEqualTo("PROCESSING")
        assertThat(record["leaseUntil"]?.n()).isEqualTo(now.plusSeconds(60).epochSecond.toString())
        assertThat(record["expiresAt"]?.n()).isEqualTo(now.plusSeconds(7 * 24 * 3600).epochSecond.toString())
    }

    @Test
    @DisplayName("処理中のイベントは、期限の間は取れない。期限が切れたら取り直せる（落ちた呼び出しの代わり）")
    fun leaseExpires() {
        val eventId = UUID.randomUUID()
        deliveries().begin(eventId)

        assertThat(deliveries(now.plusSeconds(59)).begin(eventId)).isFalse()
        assertThat(deliveries(now.plusSeconds(61)).begin(eventId)).isTrue()
    }

    @Test
    @DisplayName("済みのイベントは、期限を過ぎても取れない")
    fun completedIsFinal() {
        val eventId = UUID.randomUUID()
        deliveries().begin(eventId)
        deliveries().complete(eventId)

        assertThat(record(eventId)["status"]?.s()).isEqualTo("DONE")
        assertThat(deliveries(now.plusSeconds(3600)).begin(eventId)).isFalse()
    }

    @Test
    @DisplayName("送れなかったら記録を消し、すぐに取り直せる")
    fun releaseAllowsRetry() {
        val eventId = UUID.randomUUID()
        deliveries().begin(eventId)

        deliveries().release(eventId)

        assertThat(record(eventId)).isEmpty()
        assertThat(deliveries().begin(eventId)).isTrue()
    }

    @Test
    @DisplayName("済みの記録は消さない。期限が切れて、ほかの呼び出しが送り終えていることがある")
    fun releaseKeepsCompleted() {
        val eventId = UUID.randomUUID()
        deliveries().begin(eventId)
        deliveries().complete(eventId)

        deliveries().release(eventId)
        deliveries().release(UUID.randomUUID())

        assertThat(record(eventId)["status"]?.s()).isEqualTo("DONE")
    }
}
