package com.quizapp.ratelimit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * 利用者ごとに、要求の数を数える（DEV-125）。トークンバケットで、[RateLimit.capacity] 件まで続けて受け、
 * そのあとは [RateLimit.refill] の速さで受けられるようになる。
 *
 * **数えるのはタスクのメモリ。** タスクは 1 つで、デプロイの入れ替えの間だけ 2 つになる。その間は上限が 2 倍になるが、受け入れる。
 * タスクを増やすときは、置き場所を共有のもの（DynamoDB など）に替える
 *
 * 使われなくなった利用者の分は、満タンに戻っていれば消す。満タンのバケットは新しく作ったものと同じなので、消しても数え方は変わらない
 */
class RateLimiter(private val limit: RateLimit, private val clock: Clock) {

    private val buckets = ConcurrentHashMap<UUID, TokenBucket>()

    @Volatile private var lastSweptAt: Instant = clock.instant()

    /** 受け付けたら null。超えていたら、次に受け付けられるまでの時間 */
    fun tryAcquire(userId: UUID): Duration? {
        val now = clock.instant()
        sweep(now)
        return buckets.computeIfAbsent(userId) { TokenBucket(limit, now) }.tryTake(now)
    }

    /** 持っている利用者の数。片付けを確かめるテストで使う */
    fun size(): Int = buckets.size

    private fun sweep(now: Instant) {
        if (Duration.between(lastSweptAt, now) < SWEEP_INTERVAL) return
        lastSweptAt = now
        buckets.entries.removeIf { it.value.isFull(now) }
    }

    private companion object {
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** [capacity] 件まで続けて受け、[per] ごとに [refill] 件ずつ戻る */
data class RateLimit(val capacity: Int, val refill: Int, val per: Duration) {
    init {
        require(capacity > 0 && refill > 0 && !per.isNegative && !per.isZero) { "上限は正の値で指定してください: $this" }
    }

    /** 1 件が戻るまでのナノ秒 */
    internal val nanosPerToken: Double get() = per.toNanos().toDouble() / refill
}

/** 1 人分。残りの件数は小数で持つ。戻る速さが 1 秒に 1 件より細かくても、端数を捨てない */
private class TokenBucket(private val limit: RateLimit, now: Instant) {
    private var tokens = limit.capacity.toDouble()
    private var updatedAt = now

    @Synchronized
    fun tryTake(now: Instant): Duration? {
        refill(now)
        if (tokens >= 1) {
            tokens -= 1
            return null
        }
        return Duration.ofNanos(ceil((1 - tokens) * limit.nanosPerToken).toLong())
    }

    @Synchronized
    fun isFull(now: Instant): Boolean {
        refill(now)
        return tokens >= limit.capacity
    }

    private fun refill(now: Instant) {
        val elapsed = Duration.between(updatedAt, now).toNanos()
        if (elapsed <= 0) return
        tokens = min(limit.capacity.toDouble(), tokens + elapsed / limit.nanosPerToken)
        updatedAt = now
    }
}
