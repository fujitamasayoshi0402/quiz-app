package com.quizapp.support.fake

import com.quizapp.answer.domain.AttemptCursor
import com.quizapp.answer.domain.AttemptHistoryQuery
import com.quizapp.answer.domain.CompletedAttempt
import com.quizapp.quiz.domain.DeliveryScope
import java.time.Instant
import java.util.UUID

/**
 * メモリ上の [AttemptHistoryQuery]。
 *
 * 利用者は区別しない。本人の記録だけを返すことは、SQL の責務なので API テストが確かめる。
 * 「クイズごとに最新の回答を選ぶ」のも SQL なので、ここでは選んだあとの結果を直接持たせる。
 * 並びと続きの位置は、本物と同じ規則（終えた時刻の新しい順、同じなら ID の大きい順）で扱う。
 */
class FakeAttemptHistory : AttemptHistoryQuery {

    /** クイズの ID と、最新の回答の正誤 */
    val latestResults = mutableMapOf<UUID, Boolean>()

    private val completed = mutableListOf<CompletedAttempt>()

    fun complete(
        finishedAt: Instant = Instant.now(),
        categoryId: UUID? = null,
        difficultyId: UUID? = null,
        correctCount: Int = 0,
    ): CompletedAttempt = CompletedAttempt(
        id = UUID.randomUUID(),
        categoryId = categoryId,
        difficultyId = difficultyId,
        level = null,
        scope = DeliveryScope.ALL,
        startedAt = finishedAt,
        finishedAt = finishedAt,
        totalCount = TOTAL_COUNT,
        answeredCount = TOTAL_COUNT,
        correctCount = correctCount,
    ).also { completed += it }

    override fun findCompleted(userId: UUID, after: AttemptCursor?, limit: Int): List<CompletedAttempt> = completed
        .sortedWith(compareBy(NEWEST_FIRST) { it.cursor })
        // 並びで後ろにあるものが「続き」
        .filter { after == null || NEWEST_FIRST.compare(it.cursor, after) > 0 }
        .take(limit)

    override fun findLatestResults(userId: UUID): Map<UUID, Boolean> = latestResults.toMap()

    private companion object {
        const val TOTAL_COUNT = 10

        val NEWEST_FIRST = compareByDescending<AttemptCursor> { it.finishedAt }.thenByDescending { it.id }
    }
}
