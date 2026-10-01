package com.quizapp.quiz.domain

/**
 * クイズへの 1 回の操作が、何を変えたか（ADR-0022）。**1 回の操作で送るイベントは 1 つ。**
 *
 * 状態が変わった保存は [PUBLISHED] / [UNPUBLISHED] だけにする。中身も同時に変わっていても [UPDATED] は付けない。
 * 同じ操作の通知が 2 回届かないようにする。
 */
enum class QuizChange {
    CREATED,
    UPDATED,
    PUBLISHED,
    UNPUBLISHED,
    ;

    companion object {
        /**
         * 保存の前後から変化を決める。**何も変わっていなければ null**（イベントを送らない）。
         *
         * 画面は、変えていない項目も含めてクイズ全体を送ってくる。
         * 保存を押しただけで「更新した」と通知されないよう、中身を比べる。
         * 選択肢の ID は保存のたびに振り直されるため、本文と正解だけを並び順ごと比べる
         */
        fun between(before: Quiz, after: Quiz): QuizChange? = when {
            before.status == QuizStatus.DRAFT && after.status == QuizStatus.PUBLISHED -> PUBLISHED
            before.status == QuizStatus.PUBLISHED && after.status == QuizStatus.DRAFT -> UNPUBLISHED
            before.content() != after.content() -> UPDATED
            else -> null
        }

        private fun Quiz.content() = listOf(
            categoryId,
            difficultyId,
            question,
            explanation,
            choices.map { it.body to it.isCorrect },
        )
    }
}
