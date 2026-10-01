package com.quizapp.quiz.infrastructure.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.Target
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 実際の EventBridge（LocalStack）に送り、バスのルールから SQS に届くかを確かめる。
 *
 * 単体テスト（`EventBridgeEventBusTest`）は、SDK の要求の組み立てまでしか見ない。
 * ここでは、受け手のルールが絞り込みに使う `source` と `detail-type`、`detail` の中身が、届いた先でそのまま読めることを見る。
 * LocalStack の版は docker-compose.yml と揃える。
 *
 * 送れなかったときの扱いは、単体テストで見る。LocalStack は、無いバスへの送信も成功として返し、AWS と振る舞いが違う
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBridgeEventBusLocalStackTest {

    private val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"))
        .withServices("events", "sqs")
        .apply { start() }

    private val credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))

    private val events = EventBridgeClient.builder()
        .endpointOverride(localstack.endpoint)
        .region(Region.of(localstack.region))
        .credentialsProvider(credentials)
        .build()

    private val sqs = SqsClient.builder()
        .endpointOverride(localstack.endpoint)
        .region(Region.of(localstack.region))
        .credentialsProvider(credentials)
        .build()

    @AfterAll
    fun tearDown() {
        events.close()
        sqs.close()
        localstack.stop()
    }

    /** 送り先のバスと、そこに届いた quiz-service のイベントを SQS に流すルールを作る。受け手（DEV-98）のルールと同じ形 */
    private fun busWithQueue(): Pair<String, String> {
        val bus = "quiz-app-" + UUID.randomUUID().toString().take(8)
        events.createEventBus { it.name(bus) }
        val queueUrl = sqs.createQueue { it.queueName(bus) }.queueUrl()
        val queueArn = sqs.getQueueAttributes { it.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN) }
            .attributes().getValue(QueueAttributeName.QUEUE_ARN)
        events.putRule {
            it.name("to-queue").eventBusName(bus).eventPattern("""{"source":["quiz-app.quiz-service"]}""")
        }
        events.putTargets {
            it.rule("to-queue").eventBusName(bus).targets(Target.builder().id("queue").arn(queueArn).build())
        }
        return bus to queueUrl
    }

    private fun receiveAll(queueUrl: String, expected: Int): List<String> {
        val bodies = mutableListOf<String>()
        repeat(RECEIVE_ATTEMPTS) {
            if (bodies.size >= expected) return bodies
            sqs.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1) }
                .messages().forEach { bodies += it.body() }
        }
        return bodies
    }

    @Test
    @DisplayName("送ったイベントが、source と detail-type と detail を保ったままルールの先に届く。10 件を超えても届く")
    fun deliversThroughRule() {
        val (busName, queueUrl) = busWithQueue()
        val entries = (1..12).map {
            val id = UUID.randomUUID()
            OutboxEntry(id, UUID.randomUUID(), "QuizPublished", """{"eventId":"$id","version":1}""")
        }

        val sent = EventBridgeEventBus(events, busName).publish(entries)

        assertThat(sent).containsExactlyInAnyOrderElementsOf(entries.map { it.id })
        val delivered = receiveAll(queueUrl, entries.size).map { JsonMapper.builder().build().readTree(it) }
        assertThat(delivered).hasSize(entries.size)
        assertThat(delivered).allSatisfy {
            assertThat(it["source"].asString()).isEqualTo("quiz-app.quiz-service")
            assertThat(it["detail-type"].asString()).isEqualTo("QuizPublished")
        }
        assertThat(delivered.map { it["detail"]["eventId"].asString() })
            .containsExactlyInAnyOrderElementsOf(entries.map { it.id.toString() })
    }

    private companion object {
        const val RECEIVE_ATTEMPTS = 20
    }
}
