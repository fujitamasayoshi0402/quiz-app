package com.quizapp.answer.domain

import java.time.LocalDate

/**
 * 続けて解いた日の数（DEV-179）。**今日か昨日まで続いていれば数える。**
 *
 * 今日まだ解いていなくても、昨日まで続いていれば途切れていない。朝に開いたとき 0 日と出ると、続ける気が失せる。
 */
object StudyStreak {
    fun of(days: Set<LocalDate>, today: LocalDate): Int {
        val last = when {
            today in days -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> return 0
        }
        return generateSequence(last) { it.minusDays(1) }.takeWhile { it in days }.count()
    }
}
