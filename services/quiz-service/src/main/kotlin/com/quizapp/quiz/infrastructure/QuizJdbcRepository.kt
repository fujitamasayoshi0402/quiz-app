package com.quizapp.quiz.infrastructure

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 集約を 1 件ずつ読み書きする。何件もまとめて読むものは [QuizListJdbc] にある（選択肢を 1 本の SQL で読むため） */
@Repository
interface QuizJdbcRepository : CrudRepository<QuizEntity, UUID> {

    @Query("SELECT * FROM quiz.quizzes WHERE id = :id AND deleted_at IS NULL")
    fun findActiveById(id: UUID): QuizEntity?
}
