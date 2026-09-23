package com.quizapp.quiz.domain

import java.util.UUID

/**
 * カテゴリの永続化。実装は infrastructure 層に置く。
 *
 * **取得系は削除済みを含まない。** 削除済みを扱う操作はここに置かず、
 * [DeletionRepository] に集めている。暗黙に除外する仕組みを入れると、
 * 復活機能のように削除済みを見たい場面で抜け道を作ることになる（ADR-0009）。
 */
interface CategoryRepository {
    fun findAll(): List<Category>

    fun findById(id: UUID): Category?

    fun save(category: Category): Category
}
