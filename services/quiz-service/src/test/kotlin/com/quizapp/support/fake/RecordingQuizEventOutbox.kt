package com.quizapp.support.fake

import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizChange
import com.quizapp.quiz.domain.QuizEventOutbox

/**
 * 書いたイベントを順に残す Outbox。
 *
 * 本物は行を DB に書き、JSON の形は見本のテスト、同じトランザクションで書くことは API テストが確かめる。
 * ここで見るのは、どの操作でどのイベントが書かれたか。
 */
class RecordingQuizEventOutbox : QuizEventOutbox {
    val events = mutableListOf<Recorded>()

    override fun quizChanged(change: QuizChange, quiz: Quiz) {
        events += Recorded.Changed(change, quiz)
    }

    override fun quizzesImported(total: Int, published: Int) {
        events += Recorded.Imported(total, published)
    }

    sealed interface Recorded {
        data class Changed(val change: QuizChange, val quiz: Quiz) : Recorded

        data class Imported(val total: Int, val published: Int) : Recorded
    }
}
