package com.quizapp.answer.domain

import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryScope
import java.time.Instant
import java.util.UUID

enum class AttemptStatus {
    /** 回答中。中断しているものもこれにあたる */
    IN_PROGRESS,

    /** 最後まで解き終えた */
    COMPLETED,

    /** 途中でやめた。統計の挑戦単位の集計から外す */
    ABANDONED,
    ;

    companion object {
        fun from(value: String): AttemptStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("不明な挑戦の状態です: $value")
    }
}

/**
 * 1 回のクイズセッション。
 *
 * **出題されたクイズと順序を保持する**ことで、別の端末からでも再開できる。
 * ブラウザに置くと、回答（サーバー）と出題順（ブラウザ）で状態が 2 か所に分かれ、
 * ブラウザのデータが消えたときに再開できない回答だけがサーバーに残る。
 *
 * 出題数と並びは保持しない。出題リストが確定した時点で役目を終えるため。
 * 出題対象（[scope]）は「何を解いたか」を後から示すために残す。
 */
data class Attempt(
    val id: UUID? = null,
    val userId: UUID,
    val categoryId: UUID? = null,
    val difficultyId: UUID? = null,
    val level: Int? = null,
    val scope: DeliveryScope = DeliveryScope.ALL,
    val quizIds: List<UUID> = emptyList(),
    val status: AttemptStatus = AttemptStatus.IN_PROGRESS,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
) {
    val isInProgress: Boolean get() = status == AttemptStatus.IN_PROGRESS

    companion object {
        fun start(userId: UUID, criteria: DeliveryCriteria, quizIds: List<UUID>) = Attempt(
            userId = userId,
            categoryId = criteria.categoryId,
            difficultyId = criteria.difficultyId,
            level = criteria.level,
            scope = criteria.scope,
            quizIds = quizIds,
        )
    }
}

/**
 * 1 問への回答。
 *
 * **挑戦をまたいだ解き直しは上書きしない。** 未回答優先の出題で回答済みも出す以上、
 * 解き直しは例外ではなく通常の導線で起きる。
 * 履歴を上書きすると「間違えたあと正解した」という学習の過程が消える。
 */
data class Answer(
    val id: UUID? = null,
    val attemptId: UUID,
    val userId: UUID,
    val quizId: UUID,
    val choiceId: UUID,
    val isCorrect: Boolean,
    val answeredAt: Instant? = null,
)
