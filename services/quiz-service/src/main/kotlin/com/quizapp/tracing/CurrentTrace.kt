package com.quizapp.tracing

import io.micrometer.tracing.Tracer

/**
 * いま動いている区間の `traceparent`（ADR-0026）。Outbox の行に残し、送るときにイベントを同じトレースへつなぐ。
 *
 * 区間が無い（トレースを取っていない）ときは null
 */
class CurrentTrace(private val tracer: Tracer) {
    fun traceParent(): String? {
        val context = tracer.currentTraceContext().context() ?: return null
        return TraceHeaders.traceParent(context.traceId(), context.spanId(), context.sampled() == true)
    }
}
