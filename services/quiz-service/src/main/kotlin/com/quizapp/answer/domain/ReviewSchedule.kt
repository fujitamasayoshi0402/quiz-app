package com.quizapp.answer.domain

import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 問題 1 つを、次にいつ出すか（DEV-178、ADR-0033）。SM-2 を、正誤の 2 つしかない回答に合わせて縮めたもの。
 *
 * - 間違えたら、続けて正解した回数を 0 に戻し、今日の復習に出す。覚えやすさの係数を下げる
 * - 期日が来た問題に正解したら、間隔を 1 日 → 6 日 → 前の間隔 × 係数と延ばす
 * - **期日の前に正解しても、間隔は延ばさない。** ふつうの出題でも同じ問題に答えるため、
 *   同じ日に何度か正解しただけで間隔が一気に延びないようにする
 */
data class ReviewSchedule(
    val quizId: UUID,
    val repetitions: Int,
    val intervalDays: Int,
    val ease: Double,
    val dueOn: LocalDate,
) {
    fun after(correct: Boolean, today: LocalDate): ReviewSchedule = when {
        !correct -> copy(
            repetitions = 0,
            intervalDays = 0,
            ease = (ease - EASE_PENALTY).coerceAtLeast(MIN_EASE),
            dueOn = today,
        )

        dueOn.isAfter(today) -> this

        else -> {
            val interval = when (repetitions + 1) {
                1 -> FIRST_INTERVAL
                2 -> SECOND_INTERVAL
                else -> (intervalDays * ease).roundToInt()
            }
            copy(repetitions = repetitions + 1, intervalDays = interval, dueOn = today.plusDays(interval.toLong()))
        }
    }

    fun isDue(today: LocalDate): Boolean = !dueOn.isAfter(today)

    companion object {
        const val INITIAL_EASE = 2.5
        const val MIN_EASE = 1.3
        const val EASE_PENALTY = 0.2
        const val FIRST_INTERVAL = 1
        const val SECOND_INTERVAL = 6

        /** はじめて答えた問題。まだ一度も覚えていない状態から、その回答を当てはめる */
        fun first(quizId: UUID, correct: Boolean, today: LocalDate): ReviewSchedule =
            ReviewSchedule(quizId, repetitions = 0, intervalDays = 0, ease = INITIAL_EASE, dueOn = today)
                .after(correct, today)
    }
}
