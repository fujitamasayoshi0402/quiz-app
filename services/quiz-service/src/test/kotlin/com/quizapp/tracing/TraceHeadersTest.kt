package com.quizapp.tracing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TraceHeadersTest {

    @Test
    @DisplayName("traceparent を X-Ray のトレースヘッダに変える。記録するかの印も引き継ぐ")
    fun xrayTraceHeader() {
        assertThat(TraceHeaders.xrayTraceHeader("00-6a1f3c2b0123456789abcdef01234567-0123456789abcdef-01"))
            .isEqualTo("Root=1-6a1f3c2b-0123456789abcdef01234567;Parent=0123456789abcdef;Sampled=1")
        assertThat(TraceHeaders.xrayTraceHeader("00-6a1f3c2b0123456789abcdef01234567-0123456789abcdef-00"))
            .endsWith(";Sampled=0")
    }

    @Test
    @DisplayName("形の崩れた traceparent は変えない")
    fun malformed() {
        for (value in listOf("", "00-abc-def-01", "00-6A1F3C2B0123456789ABCDEF01234567-0123456789abcdef-01", "x")) {
            assertThat(TraceHeaders.xrayTraceHeader(value)).isNull()
        }
    }

    @Test
    @DisplayName("trace ID の先頭 8 桁は、作った時刻（エポック秒）")
    fun traceIdStartsWithEpochSeconds() {
        val now = Instant.parse("2026-10-04T00:00:00Z")
        val traceId = XrayCompatibleIdGenerator(Clock.fixed(now, ZoneOffset.UTC)).generateTraceId()

        assertThat(traceId).matches("[0-9a-f]{32}")
        assertThat(traceId.take(8).toLong(16)).isEqualTo(now.epochSecond)
    }
}
