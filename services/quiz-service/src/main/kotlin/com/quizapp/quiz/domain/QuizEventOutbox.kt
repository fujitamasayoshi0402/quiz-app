package com.quizapp.quiz.domain

/**
 * クイズのイベントを、変更と同じトランザクションで Outbox に書く（ADR-0022）。
 *
 * **トランザクションの中で呼ぶ。** 変更が失敗すれば、イベントも残らない。送るのは別の仕組み（DEV-97）で、ここは書くだけ
 */
interface QuizEventOutbox {
    /** [quiz] は保存したあとのもの（ID がある） */
    fun quizChanged(change: QuizChange, quiz: Quiz)

    /** 一括インポート。1 件ずつは書かず、件数だけを 1 つにまとめる */
    fun quizzesImported(total: Int, published: Int)
}
