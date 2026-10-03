package com.quizapp.tracing

import io.opentelemetry.sdk.trace.IdGenerator
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom

/**
 * X-Ray が受け付ける形の trace ID を作る（ADR-0026）。
 *
 * X-Ray の trace ID は、先頭の 8 桁（16 進）が作った時刻（エポック秒）で、残りの 24 桁が乱数。
 * OpenTelemetry の既定はすべて乱数で、先頭が時刻として読めないため、X-Ray が捨てることがある。
 * W3C の `traceparent` の trace ID（32 桁）としても、そのまま使える。
 * web の proxy も同じ形で作る（`apps/web/src/lib/trace-context.ts`）
 */
class XrayCompatibleIdGenerator(private val clock: Clock = Clock.systemUTC()) : IdGenerator {
    override fun generateTraceId(): String {
        val random = ThreadLocalRandom.current()
        val epochSeconds = clock.instant().epochSecond
        return "%08x%08x%016x".format(epochSeconds, random.nextInt(), random.nextLong())
    }

    override fun generateSpanId(): String = IdGenerator.random().generateSpanId()
}
