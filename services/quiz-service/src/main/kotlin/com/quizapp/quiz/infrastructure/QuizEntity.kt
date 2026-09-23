package com.quizapp.quiz.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * クイズと選択肢を 1 つの集約として扱う。
 *
 * `choices` は [MappedCollection] で紐づける。keyColumn に `sort_order` を指定しているため、
 * **リストの添字がそのまま表示順として保存される**。並べ替えはリストの順序を変えるだけでよい。
 */
@Table(schema = "quiz", name = "quizzes")
data class QuizEntity(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    val explanation: String,
    val explanationImageKey: String? = null,
    val status: String,
    @MappedCollection(idColumn = "quiz_id", keyColumn = "sort_order")
    val choices: List<ChoiceEntity> = emptyList(),
    @ReadOnlyProperty val createdAt: Instant? = null,
    @ReadOnlyProperty val updatedAt: Instant? = null,
    @ReadOnlyProperty val deletedAt: Instant? = null,
)

/**
 * 選択肢。クイズの集約に属するため単独のリポジトリを持たない。
 *
 * `id` と `quiz_id`、`sort_order` は Spring Data JDBC が管理するので宣言しない。
 * `tenant_id` は複合外部キーの構成要素であり、親と同じ値を明示的に設定する必要がある。
 */
@Table(schema = "quiz", name = "choices")
data class ChoiceEntity(
    val tenantId: UUID,
    val body: String,
    val isCorrect: Boolean,
)
