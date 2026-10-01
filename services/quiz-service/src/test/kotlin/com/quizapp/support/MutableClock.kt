package com.quizapp.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 進められる時計。時間に沿って動きを変えるもの（拾い直しなど）の単体テストで使う */
class MutableClock(private var now: Instant = Instant.parse("2026-10-01T00:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
