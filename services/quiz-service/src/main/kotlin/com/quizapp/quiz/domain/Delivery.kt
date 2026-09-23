package com.quizapp.quiz.domain

import java.util.UUID

/**
 * 出題対象。**並び・出題数とは独立した軸**として扱う。
 *
 * 「全問出題 / 出題数指定 / 未回答優先」のように 1 つのモードにまとめると、
 * 実際には並び順しか違わないものが別の選択肢として並び、出題数との関係も見えなくなる。
 */
enum class DeliveryScope {
    /** 条件に合うクイズすべてから選ぶ */
    ALL,

    /** 未回答を先に出し、尽きたら回答済みで埋める */
    UNANSWERED,

    /**
     * 未回答だけを出す。尽きたらそこで終了する。
     *
     * 「このカテゴリを一周する」使い方では残りが何問あるかが意味を持つため、
     * 回答済みを混ぜると用をなさない。
     */
    UNANSWERED_ONLY,
    ;

    companion object {
        fun from(value: String): DeliveryScope =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "出題対象は all / unanswered / unanswered_only を指定してください: $value",
                )
    }
}

/** 出題の並び。 */
enum class DeliveryOrder {
    RANDOM,

    /** 難易度のレベル順・並び順・作成順。教材のように順序に意味がある場合に使う */
    REGISTERED,
    ;

    companion object {
        fun from(value: String): DeliveryOrder =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("並びは random / registered を指定してください: $value")
    }
}

/**
 * 出題の条件。
 *
 * [limit] の既定値は**上限**であり 10 ではない。
 * サーバーが小さな既定値を持つと、「すべて出す」つもりの指定が黙って切られる。
 * 画面の初期値（10 問）はフロントエンドが持つ。
 */
data class DeliveryCriteria(
    val categoryId: UUID? = null,
    val difficultyId: UUID? = null,
    val level: Int? = null,
    val scope: DeliveryScope = DeliveryScope.ALL,
    val order: DeliveryOrder = DeliveryOrder.RANDOM,
    val limit: Int = MAX_QUIZ_COUNT,
) {
    init {
        require(limit in 1..MAX_QUIZ_COUNT) { "出題数は 1 以上 $MAX_QUIZ_COUNT 以下で指定してください" }
    }

    companion object {
        const val MAX_QUIZ_COUNT = 100
    }
}

/**
 * 出題されたクイズ。**正解の情報も解説も持たない。**
 *
 * ドメインの [Quiz] をそのまま返すと `isCorrect` が漏れる。
 * 型を分けることで、レスポンスの組み立てで気をつける必要をなくしている。
 */
data class DeliveredQuiz(
    val id: UUID,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    val choices: List<DeliveredChoice>,
)

data class DeliveredChoice(
    val id: UUID,
    val body: String,
)

/**
 * 採点に必要な情報。**回答したあとにだけ渡る。**
 *
 * 正誤の判定そのものは answer モジュールが行う（ADR-0004）。
 * quiz 側は「どれが正解か」という事実だけを渡し、採点の責務は持たない。
 */
data class AnswerKey(
    val quizId: UUID,
    val correctChoiceId: UUID,
    val choiceIds: Set<UUID>,
    val explanation: String,
)
