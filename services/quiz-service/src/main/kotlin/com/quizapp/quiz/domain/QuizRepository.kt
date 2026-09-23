package com.quizapp.quiz.domain

import java.util.UUID

interface QuizRepository {
    /** 削除済みを除くクイズを返す。カテゴリと難易度で絞り込める。 */
    fun search(categoryId: UUID? = null, difficultyId: UUID? = null, status: QuizStatus? = null): List<Quiz>

    fun findById(id: UUID): Quiz?

    fun save(quiz: Quiz): Quiz

    fun softDelete(id: UUID): Boolean
}
