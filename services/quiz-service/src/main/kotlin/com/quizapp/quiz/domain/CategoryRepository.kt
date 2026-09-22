package com.quizapp.quiz.domain

import java.util.UUID

/**
 * カテゴリの永続化。実装は infrastructure 層に置く。
 *
 * 取得系は**削除済みを含まない**。削除済みを扱う操作は名前で区別する
 * （[findAllIncludingDeleted]）。暗黙に除外する仕組みを入れると、
 * 復活機能のように削除済みを見たい場面で抜け道を作ることになる（ADR-0009）。
 */
interface CategoryRepository {
    fun findAll(): List<Category>

    fun findById(id: UUID): Category?

    fun findAllIncludingDeleted(): List<Category>

    fun save(category: Category): Category

    /** 論理削除する。削除済み、または存在しない場合は false。 */
    fun softDelete(id: UUID): Boolean
}
