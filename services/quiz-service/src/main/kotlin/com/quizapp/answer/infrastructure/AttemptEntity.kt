package com.quizapp.answer.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * 挑戦と出題リストを 1 つの集約として扱う。
 *
 * `quizzes` の keyColumn に `sort_order` を指定しているため、
 * **リストの添字がそのまま出題順として保存される**。
 *
 * 状態の変更は集約ごと保存せず [AttemptJdbcRepository.finish] で直接更新する。
 * Spring Data JDBC は集約を保存するたびに子を全削除・全挿入するため、
 * 状態を変えるだけで出題リストを書き直すことになる。
 */
@Table(schema = "answer", name = "attempts")
data class AttemptEntity(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val userId: UUID,
    val categoryId: UUID? = null,
    val difficultyId: UUID? = null,
    val level: Int? = null,
    val scope: String,
    val status: String,
    @MappedCollection(idColumn = "attempt_id", keyColumn = "sort_order")
    val quizzes: List<AttemptQuizEntity> = emptyList(),
    @ReadOnlyProperty val startedAt: Instant? = null,
    @ReadOnlyProperty val finishedAt: Instant? = null,
)

/**
 * 出題されたクイズ 1 件。挑戦の集約に属するため単独のリポジトリを持たない。
 *
 * `attempt_id` と `sort_order` は Spring Data JDBC が管理するので宣言しない。
 * `quiz_id` に外部キーを貼っていないのは、answer モジュールを分離するときの障害になるため（ADR-0004）。
 * `tenant_id` は複合外部キーの構成要素であり、親と同じ値を明示的に設定する必要がある。
 */
@Table(schema = "answer", name = "attempt_quizzes")
data class AttemptQuizEntity(val tenantId: UUID, val quizId: UUID)

@Table(schema = "answer", name = "answers")
data class AnswerEntity(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val attemptId: UUID,
    val userId: UUID,
    val quizId: UUID,
    val choiceId: UUID,
    val isCorrect: Boolean,
    @ReadOnlyProperty val answeredAt: Instant? = null,
)
