package com.quizapp.tracing

import io.micrometer.observation.ObservationPredicate
import io.micrometer.tracing.Tracer
import net.ttddyy.observation.tracing.DataSourceBaseContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext

/**
 * 分散トレース（ADR-0026）。Micrometer Tracing を OpenTelemetry で動かし、同じタスクのコレクタへ OTLP で送る。
 * コレクタが X-Ray へ送る。送り先は環境変数（`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`）で決まり、
 * 無ければ送らない（ローカルとテスト）。送らなくても trace ID は作られ、ログに載る
 */
@Configuration
class TracingConfiguration {

    /** X-Ray が受け付ける形の trace ID にする */
    @Bean
    fun xrayCompatibleIds(): SdkTracerProviderBuilderCustomizer =
        SdkTracerProviderBuilderCustomizer { it.setIdGenerator(XrayCompatibleIdGenerator()) }

    /**
     * トレースにしないもの。
     * - コンテナのヘルスチェック（`/actuator`）。15 秒ごとに届き、トレースの大半を占める
     * - 定期的な処理（Outbox の拾い直し、データの整合性の確認）。1 分ごとに動き、ほとんどは何もしない
     * - 要求の外で動いた DB の操作。定期的な処理を除いても、その中の DB の操作が親のないトレースとして残るため
     */
    @Bean
    fun skipHousekeeping(): ObservationPredicate = ObservationPredicate { name, context ->
        when {
            name == SCHEDULED_TASK -> false
            context is ServerRequestObservationContext -> context.carrier?.requestURI?.startsWith("/actuator") != true
            context is DataSourceBaseContext -> context.parentObservation != null
            else -> true
        }
    }

    /** テストではトレースを取らず、Tracer が無いことがある。そのときは区間なしとして扱う */
    @Bean
    fun currentTrace(tracer: ObjectProvider<Tracer>): CurrentTrace = CurrentTrace(tracer.getIfAvailable { Tracer.NOOP })

    private companion object {
        /** Spring の `@Scheduled` と `SchedulingConfigurer` で登録した処理 */
        const val SCHEDULED_TASK = "tasks.scheduled.execution"
    }
}
