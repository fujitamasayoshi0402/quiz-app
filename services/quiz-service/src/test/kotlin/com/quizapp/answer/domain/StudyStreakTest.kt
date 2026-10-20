package com.quizapp.answer.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 続けて解いた日の数え方（DEV-179） */
class StudyStreakTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun daysAgo(vararg days: Long) = days.map { today.minusDays(it) }.toSet()

    @Test
    @DisplayName("今日まで続いていれば、今日から数える")
    fun countsFromToday() {
        assertThat(StudyStreak.of(daysAgo(0, 1, 2, 4), today)).isEqualTo(3)
    }

    @Test
    @DisplayName("今日まだ解いていなくても、昨日まで続いていれば途切れていない")
    fun countsFromYesterday() {
        assertThat(StudyStreak.of(daysAgo(1, 2), today)).isEqualTo(2)
    }

    @Test
    @DisplayName("昨日も解いていなければ 0")
    fun brokenStreak() {
        assertThat(StudyStreak.of(daysAgo(2, 3), today)).isZero()
        assertThat(StudyStreak.of(emptySet(), today)).isZero()
    }
}
