package com.quizapp.answer.domain

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 学習の 1 日の区切り。**日本時間で固定する**（DEV-178）。
 *
 * 利用者のタイムゾーンは持っていない。UTC で区切ると、日付は日本の朝 9 時に変わり、
 * 朝 8 時に正解した問題の「明日」が、1 時間後に来てしまう（ADR-0033）。
 */
object StudyDay {
    val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))
}
