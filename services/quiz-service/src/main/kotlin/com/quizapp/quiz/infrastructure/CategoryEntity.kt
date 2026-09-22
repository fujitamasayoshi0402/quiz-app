package com.quizapp.quiz.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * `quiz.categories` に対応する永続化用の型。
 * ドメインモデル（[com.quizapp.quiz.domain.Category]）と分けているのは、
 * 永続化の都合（テナント、タイムスタンプ、論理削除）をドメインに持ち込まないため。
 */
// スキーマとテーブル名は分けて指定する。"quiz.categories" と書くとドットを含む
// 1 つの識別子として扱われ、テーブルが見つからない
@Table(schema = "quiz", name = "categories")
data class CategoryEntity(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val name: String,
    val description: String? = null,
    val sortOrder: Int = 0,
    // DB 側で管理する値。Spring Data JDBC は既定で全カラムを INSERT しようとするため、
    // 除外しないと NULL が渡って NOT NULL 制約に当たる。
    // created_at と updated_at はデフォルト値とトリガーが、deleted_at は論理削除の更新が設定する
    @ReadOnlyProperty val createdAt: Instant? = null,
    @ReadOnlyProperty val updatedAt: Instant? = null,
    @ReadOnlyProperty val deletedAt: Instant? = null,
)
