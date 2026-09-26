package com.quizapp.answer.domain

import com.quizapp.quiz.domain.AnswerKey
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.PlayableCategory
import java.util.UUID

/**
 * answer モジュールから見たクイズ。**実装は quiz モジュールに置く。**
 *
 * 挑戦は answer が持つが、何を出すか・どれが正解かを知っているのは quiz である。
 * [ADR-0004](../../../../../../../../docs/adr/0004-split-services-incrementally.md) が
 * モジュールをまたぐテーブル結合を禁止しているため、インターフェースを介して受け取る。
 *
 * DEV-21 の [com.quizapp.quiz.domain.AnsweredQuizzes] と向きが逆の 2 例目にあたる。
 * **インターフェースは常に呼ぶ側が持ち、実装を呼ばれる側に置く。**
 * 分離するときは実装を HTTP 呼び出しに差し替えるだけで、answer 側は変わらない。
 */
interface QuizCatalog {

    /** 条件に従って出題するクイズを選ぶ。正解も解説も含まない。 */
    fun select(criteria: DeliveryCriteria, userId: UUID): List<DeliveredQuiz>

    /**
     * 指定 ID のうち、いま出題できるものだけを返す。
     *
     * 再開時の検証に使う。**返る件数が減りうる。**
     * quiz スキーマへ外部キーを貼っていないため、出題済みのクイズは削除も非公開もされうる。
     */
    fun findDeliverable(quizIds: List<UUID>): List<DeliveredQuiz>

    /**
     * 採点に必要な情報を ID 引きで返す。
     *
     * **回答を受け取ったあとにだけ呼ぶ。** 出題時に呼ぶと正解が経路に乗る。
     * 判定そのものは answer 側で行う。
     */
    fun findAnswerKeys(quizIds: List<UUID>): Map<UUID, AnswerKey>

    /** 出題できるカテゴリ。公開済みのクイズがあるものだけを、並び順どおりに返す。 */
    fun findPlayableCategories(): List<PlayableCategory>

    /**
     * 指定したクイズのうち、いま出題できるものが属するカテゴリ。
     *
     * 履歴の集計に使う。**削除・非公開のクイズは返らない。**
     * 返したカテゴリは、必ず [findPlayableCategories] にも現れる。
     */
    fun findCategoryIds(quizIds: Collection<UUID>): Map<UUID, UUID>
}
