package com.quizapp.quiz.domain

/**
 * クイズ 1 問に寄せられた回答の数と、そのうち正解だった数。テナントの全員の回答を数える（DEV-158）。
 *
 * 管理者が、間違えやすい問題を見つけるために使う。率ではなく数で持つのは、
 * 回答が少ないときに率を出すかどうかを、見せる側が決められるようにするため。
 */
data class AnswerTally(val answered: Int, val correct: Int) {
    companion object {
        val NONE = AnswerTally(answered = 0, correct = 0)
    }
}
