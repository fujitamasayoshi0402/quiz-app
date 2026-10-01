package com.quizapp.quiz.infrastructure.outbox

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import java.net.URI
import java.time.Clock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * イベントを送る仕組みを組み立てる（ADR-0022）。起動のときには AWS にも DB にも接続しない。
 *
 * 拾い直しは、起動しても動き出さない。利用者が DB を使ってから動く（[DatabaseActivity]）
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(EventsProperties::class)
class EventsConfiguration(private val properties: EventsProperties) {

    private val clock = Clock.systemUTC()

    @Bean
    fun eventBus(): EventBus = EventBridgeEventBus(eventBridgeClient(), properties.busName)

    @Bean
    fun databaseActivity(): DatabaseActivity = DatabaseActivity(clock)

    @Bean
    fun databaseActivityFilter(databaseActivity: DatabaseActivity): FilterRegistrationBean<DatabaseActivityFilter> =
        FilterRegistrationBean(DatabaseActivityFilter(databaseActivity)).apply { order = DatabaseActivityFilter.ORDER }

    /**
     * 送るスレッドは 1 本。イベントは操作のたびに 1 つで、急がない。
     * 待ち行列があふれたら受け付けず、Outbox に残して拾い直しに任せる。メモリを際限なく使わない
     */
    @Bean
    fun outboxPublisher(eventBus: EventBus, outboxRows: OutboxRows): OutboxPublisher = OutboxPublisher(
        eventBus,
        outboxRows,
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(PUBLISH_QUEUE_CAPACITY)) { runnable ->
            Thread(runnable, "outbox-publisher").apply { isDaemon = true }
        },
    )

    @Bean
    fun outboxRelay(eventBus: EventBus, outboxRows: OutboxRows, databaseActivity: DatabaseActivity): OutboxRelay =
        OutboxRelay(outboxRows, eventBus, databaseActivity, properties.relay, clock)

    /** 拾い直しを定期的に呼ぶ。最初の回も [EventsProperties.Relay.interval] の後 */
    @Bean
    fun outboxRelaySchedule(outboxRelay: OutboxRelay): SchedulingConfigurer = SchedulingConfigurer { registrar ->
        val relay = properties.relay
        if (relay.enabled) {
            registrar.addFixedDelayTask(
                FixedDelayTask(outboxRelay::tick, relay.interval, relay.interval),
            )
        }
    }

    /**
     * 接続先を変えている（LocalStack）ときは、ダミーの認証情報を使う。
     * 手元の AWS の認証情報（SSO など）を、ローカルの EventBridge に送らない
     */
    private fun eventBridgeClient(): EventBridgeClient {
        val endpoint = properties.endpoint.ifBlank { null }?.let(URI::create)
        return EventBridgeClient.builder()
            .region(Region.of(properties.region))
            .apply {
                if (endpoint != null) {
                    endpointOverride(endpoint)
                    credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                } else {
                    credentialsProvider(DefaultCredentialsProvider.builder().build())
                }
            }
            .build()
    }

    private companion object {
        const val PUBLISH_QUEUE_CAPACITY = 1000
    }
}
