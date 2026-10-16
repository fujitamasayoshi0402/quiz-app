package com.quizapp.answer.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 次に出す日の決め方（ADR-0033）。式そのものなので、DB を使わずに 1 つずつ確かめる */
class ReviewScheduleTest {

    private val quiz = UUID.randomUUID()
    private val today = LocalDate.of(2026, 10, 16)

    @Test
    @DisplayName("はじめて間違えたら、今日の復習に出す")
    fun firstMissIsDueToday() {
        val schedule = ReviewSchedule.first(quiz, correct = false, today)

        assertThat(schedule.dueOn).isEqualTo(today)
        assertThat(schedule.isDue(today)).isTrue()
        assertThat(schedule.ease).isEqualTo(ReviewSchedule.INITIAL_EASE - ReviewSchedule.EASE_PENALTY)
    }

    @Test
    @DisplayName("期日に正解するたびに、間隔を 1 日 → 6 日 → 前の間隔 × 係数と延ばす")
    fun correctAnswersStretchTheInterval() {
        val first = ReviewSchedule.first(quiz, correct = true, today)
        assertThat(first.dueOn).isEqualTo(today.plusDays(1))

        val second = first.after(correct = true, first.dueOn)
        assertThat(second.intervalDays).isEqualTo(ReviewSchedule.SECOND_INTERVAL)
        assertThat(second.dueOn).isEqualTo(first.dueOn.plusDays(6))

        val third = second.after(correct = true, second.dueOn)
        // 6 日 × 2.5 = 15 日
        assertThat(third.intervalDays).isEqualTo(15)
        assertThat(third.repetitions).isEqualTo(3)
    }

    @Test
    @DisplayName("期日の前に正解しても、間隔は延ばさない")
    fun earlyCorrectAnswerKeepsTheSchedule() {
        val first = ReviewSchedule.first(quiz, correct = true, today)

        assertThat(first.after(correct = true, today)).isEqualTo(first)
    }

    @Test
    @DisplayName("間違えたら、続けて正解した回数を 0 に戻して今日に出し、係数を下げる。係数は 1.3 より下げない")
    fun missResetsTheSchedule() {
        val learned = ReviewSchedule(quiz, repetitions = 4, intervalDays = 30, ease = 1.4, dueOn = today.plusDays(10))

        val missed = learned.after(correct = false, today)

        assertThat(missed.repetitions).isZero()
        assertThat(missed.intervalDays).isZero()
        assertThat(missed.dueOn).isEqualTo(today)
        assertThat(missed.ease).isEqualTo(ReviewSchedule.MIN_EASE)
        // 戻したあとに正解すると、また 1 日から
        assertThat(missed.after(correct = true, today).dueOn).isEqualTo(today.plusDays(1))
    }

    @Test
    @DisplayName("日の区切りは日本時間の 0 時")
    fun studyDayTurnsAtMidnightInJapan() {
        val before = Clock.fixed(Instant.parse("2026-10-16T14:59:59Z"), ZoneOffset.UTC)
        val after = Clock.fixed(Instant.parse("2026-10-16T15:00:00Z"), ZoneOffset.UTC)

        assertThat(StudyDay.today(before)).isEqualTo(LocalDate.of(2026, 10, 16))
        assertThat(StudyDay.today(after)).isEqualTo(LocalDate.of(2026, 10, 17))
    }
}
