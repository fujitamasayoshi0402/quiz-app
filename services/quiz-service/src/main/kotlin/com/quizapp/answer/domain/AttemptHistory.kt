package com.quizapp.answer.domain

import com.quizapp.quiz.domain.DeliveryScope
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * 履歴の読み取り。挑戦の集約（[AttemptRepository]）とは分ける。
 *
 * 集約は 1 件ずつ読み書きする形で、一覧と集計には向かない。
 * 数えるのは SQL に任せ、表示に要る形で受け取る。
 * **どちらも利用者本人の記録だけを返す。** テナントは行レベルセキュリティが絞る。
 */
interface AttemptHistoryQuery {

    /** 完了した挑戦を、終えた時刻の新しい順に返す。[after] より古いものから [limit] 件。 */
    fun findCompleted(userId: UUID, after: AttemptCursor?, limit: Int): List<CompletedAttempt>

    /**
     * クイズごとの、最新の回答の正誤。
     *
     * **挑戦の状態を問わない。** 途中でやめた挑戦でも、解いた事実は消えない（回答単位の集計）。
     * 同じクイズに何度も答えていれば、最後の 1 回だけを見る。
     */
    fun findLatestResults(userId: UUID): Map<UUID, Boolean>
}

/**
 * 完了した挑戦 1 件と、その成績。
 *
 * 成績は記録した回答から数える。**出題したクイズがあとで消えても変わらない。**
 */
data class CompletedAttempt(
    val id: UUID,
    val categoryId: UUID?,
    val difficultyId: UUID?,
    val level: Int?,
    val scope: DeliveryScope,
    val startedAt: Instant,
    val finishedAt: Instant,
    val totalCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
) {
    val cursor: AttemptCursor get() = AttemptCursor(finishedAt, id)
}

/**
 * 一覧の続きの位置。**終えた時刻と ID の組で表す。**
 *
 * 件数で飛ばす（OFFSET）と、読んでいる間に挑戦を終えたとき 1 件ずれて、同じ挑戦が 2 ページに出る。
 * 時刻だけでは、同じ時刻に終えた挑戦の前後が決まらない。
 *
 * API では中身の見えない文字列として渡す。クライアントに組み立てさせないため、形を変えても API は変わらない。
 */
data class AttemptCursor(val finishedAt: Instant, val id: UUID) {

    fun encode(): String = ENCODER.encodeToString("$finishedAt$SEPARATOR$id".toByteArray())

    companion object {
        private const val SEPARATOR = '|'
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun decode(value: String): AttemptCursor = try {
            val parts = String(Base64.getUrlDecoder().decode(value)).split(SEPARATOR, limit = 2)
            require(parts.size == 2)
            AttemptCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]))
        } catch (e: IllegalArgumentException) {
            throw invalid(e)
        } catch (e: DateTimeParseException) {
            throw invalid(e)
        }

        // 壊れた値を 500 にしない。受け取った値は、文字化けしたまま応答に載るので繰り返さない
        private fun invalid(cause: Exception) = IllegalArgumentException("続きの位置の指定が正しくありません", cause)
    }
}
