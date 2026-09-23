package com.quizapp.quiz.domain

import java.util.UUID

interface QuizRepository {
    /** 削除済みを除くクイズを返す。カテゴリと難易度で絞り込める。 */
    fun search(categoryId: UUID? = null, difficultyId: UUID? = null, status: QuizStatus? = null): List<Quiz>

    fun findById(id: UUID): Quiz?

    /** 出題の候補。公開済みのみを返す。 */
    fun findPublishedCandidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz>

    /**
     * 指定 ID のうち、いま出題できるものだけを返す。
     *
     * 中断した挑戦を再開するときに使う。**削除・非公開になったクイズは返らない**ので、
     * 呼び出し側は件数が減りうることを前提にする。
     */
    fun findPublishedByIds(ids: List<UUID>): List<Quiz>

    fun save(quiz: Quiz): Quiz
}
