package com.quizapp.quiz.domain

import java.util.UUID

/**
 * クイズの分類。階層は持たない（docs/domain-model.md）。
 *
 * テナントを属性として持たない。どのテナントの文脈で操作しているかは
 * [com.quizapp.tenant.TenantContext] が保持し、永続化層が付与する。
 * ドメインモデルにテナントを持たせると、保存のたびに正しい値が入っているかを
 * 呼び出し側で確認することになり、付け替えの余地も生まれる。
 */
data class Category(
    val id: UUID? = null,
    val name: String,
    val description: String? = null,
    val sortOrder: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "カテゴリ名を入力してください" }
        require(name.length <= MAX_NAME_LENGTH) { "カテゴリ名は $MAX_NAME_LENGTH 文字以内で入力してください" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
