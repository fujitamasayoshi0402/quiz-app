package com.quizapp.answer.domain

import java.util.UUID

/**
 * 「あとで見直す」印（DEV-159）。**付け外しできるのは本人だけ**で、利用者は常に要求の主から取る。
 *
 * 印の付いた問題だけを出す出題は、quiz 側が [com.quizapp.quiz.domain.AnsweredQuizzes.filterBookmarked] で絞る。
 */
interface BookmarkRepository {
    /** 印を付けたクイズ。消えたクイズへの印も含む */
    fun findQuizIds(userId: UUID): Set<UUID>

    /** 付ける。付いていれば何もしない */
    fun add(userId: UUID, quizId: UUID)

    /** 外す。付いていなければ何もしない */
    fun remove(userId: UUID, quizId: UUID)
}
