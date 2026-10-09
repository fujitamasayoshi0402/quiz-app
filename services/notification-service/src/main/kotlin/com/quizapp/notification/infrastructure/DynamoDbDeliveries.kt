package com.quizapp.notification.infrastructure

import com.quizapp.notification.Deliveries
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 重複を捨てる記録を DynamoDB に持つ（ADR-0022 の 4）。手順は Powertools の冪等性の機能と同じ考え方で、自分で書く。
 *
 * | 項目         | 中身                                                                       |
 * | ------------ | -------------------------------------------------------------------------- |
 * | `eventId`    | キー。Outbox の行の ID で、何度送り直しても変わらない                      |
 * | `status`     | `PROCESSING`（送っている）/ `DONE`（済み）                                 |
 * | `leaseUntil` | 処理中の期限（エポック秒）。過ぎたら、落ちた呼び出しの代わりに取り直せる   |
 * | `expiresAt`  | TTL（エポック秒）。アーカイブの保持期間（7 日）と揃え、流し直しても二重に届かない |
 */
class DynamoDbDeliveries(private val dynamo: DynamoDbClient, private val table: String, private val clock: Clock) :
    Deliveries {
    override fun begin(eventId: UUID): Boolean {
        val now = clock.instant()
        return try {
            dynamo.putItem {
                it.tableName(table)
                    .item(
                        mapOf(
                            "eventId" to s(eventId.toString()),
                            "status" to s(PROCESSING),
                            "leaseUntil" to n(now.plus(LEASE).epochSecond),
                            "expiresAt" to n(now.plus(RETENTION).epochSecond),
                        ),
                    )
                    .conditionExpression(
                        "attribute_not_exists(eventId) OR (#status = :processing AND leaseUntil < :now)",
                    )
                    .expressionAttributeNames(mapOf("#status" to "status"))
                    .expressionAttributeValues(mapOf(":processing" to s(PROCESSING), ":now" to n(now.epochSecond)))
            }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }

    override fun complete(eventId: UUID) {
        dynamo.updateItem {
            it.tableName(table)
                .key(key(eventId))
                .updateExpression("SET #status = :done, expiresAt = :expiresAt REMOVE leaseUntil")
                .expressionAttributeNames(mapOf("#status" to "status"))
                .expressionAttributeValues(
                    mapOf(":done" to s(DONE), ":expiresAt" to n(clock.instant().plus(RETENTION).epochSecond)),
                )
        }
    }

    override fun release(eventId: UUID) {
        try {
            // 済みの記録は消さない。処理中の期限が切れ、ほかの呼び出しが取り直して送り終えていることがある
            dynamo.deleteItem {
                it.tableName(table)
                    .key(key(eventId))
                    .conditionExpression("#status = :processing")
                    .expressionAttributeNames(mapOf("#status" to "status"))
                    .expressionAttributeValues(mapOf(":processing" to s(PROCESSING)))
            }
        } catch (_: ConditionalCheckFailedException) {
            // 記録が無いか、済みになっている
        }
    }

    private fun key(eventId: UUID) = mapOf("eventId" to s(eventId.toString()))

    private fun s(value: String) = AttributeValue.fromS(value)

    private fun n(value: Long) = AttributeValue.fromN(value.toString())

    companion object {
        const val PROCESSING = "PROCESSING"
        const val DONE = "DONE"

        /**
         * 処理中の期限。**Lambda の時間切れ（Terraform の timeout、30 秒）より長く、非同期呼び出しの再試行の間隔（約 1 分）より短くする。**
         * - 短いと、送っている途中で、同じイベントの別の呼び出しが取り直す
         * - 長いと、時間切れで落ちた（記録を消せなかった）ときの再試行が、処理中として捨てられる。DLQ にも入らず、通知が黙って消える
         */
        val LEASE: Duration = Duration.ofMinutes(1)

        /** 記録を残す期間。バスのアーカイブの保持期間と揃える */
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
