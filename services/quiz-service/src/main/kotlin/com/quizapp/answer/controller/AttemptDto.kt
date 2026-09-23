package com.quizapp.answer.controller

import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryOrder
import com.quizapp.quiz.domain.DeliveryScope
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

/**
 * 挑戦の開始リクエスト。
 *
 * [limit] を省略すると上限まで返す。サーバーが小さな既定値を持つと、
 * 「すべて出す」つもりの指定が黙って切られる。画面の初期値はフロントエンドが持つ。
 */
data class StartAttemptRequest(
    val categoryId: UUID? = null,
    val difficultyId: UUID? = null,
    @field:Min(1, message = "レベルは 1 以上を指定してください")
    val level: Int? = null,
    val scope: String = "all",
    val order: String = "random",
    @field:Min(1, message = "出題数は 1 以上を指定してください")
    @field:Max(DeliveryCriteria.MAX_QUIZ_COUNT.toLong(), message = "出題数は 100 以下を指定してください")
    val limit: Int? = null,
    /** 中断中の挑戦を破棄して始めるか。既定では破棄せず 409 を返す */
    val discardInProgress: Boolean = false,
) {
    fun toCriteria() = DeliveryCriteria(
        categoryId = categoryId,
        difficultyId = difficultyId,
        level = level,
        scope = DeliveryScope.from(scope),
        order = DeliveryOrder.from(order),
        limit = limit ?: DeliveryCriteria.MAX_QUIZ_COUNT,
    )
}

data class AnswerRequest(val quizId: UUID, val choiceId: UUID)
