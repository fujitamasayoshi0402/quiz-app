package com.quizapp.tracing

/**
 * トレースの文脈を、ほかの仕組みへ渡す形に変える（ADR-0026）。
 *
 * - W3C の `traceparent`（`00-<trace ID>-<span ID>-<flags>`）。Outbox の行に残す
 * - X-Ray のトレースヘッダ（`Root=1-<8 桁>-<24 桁>;Parent=<span ID>;Sampled=<0|1>`）。EventBridge の `PutEvents` の `TraceHeader` に渡す。
 *   EventBridge はこれを Lambda に渡し、Lambda の区間が同じトレースにつながる
 */
object TraceHeaders {
    private val TRACE_PARENT = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")

    fun traceParent(traceId: String, spanId: String, sampled: Boolean): String =
        "00-$traceId-$spanId-${if (sampled) "01" else "00"}"

    /** 形の崩れた値は null。トレースが切れるだけで、イベントは送る */
    fun xrayTraceHeader(traceParent: String): String? {
        val match = TRACE_PARENT.matchEntire(traceParent) ?: return null
        val (traceId, spanId, flags) = match.destructured
        val sampled = flags.toInt(radix = 16) and SAMPLED_FLAG != 0
        return "Root=1-${traceId.take(XRAY_EPOCH_DIGITS)}-${traceId.drop(XRAY_EPOCH_DIGITS)};" +
            "Parent=$spanId;Sampled=${if (sampled) 1 else 0}"
    }

    private const val SAMPLED_FLAG = 0x01
    private const val XRAY_EPOCH_DIGITS = 8
}
