package com.quizapp.quiz.domain

import java.util.UUID

/**
 * 回答済みのクイズを判定する。**実装は answer モジュールに置く。**
 *
 * 未回答優先の出題は回答履歴を必要とするが、[ADR-0004](../../../../../../../../docs/adr/0004-split-services-incrementally.md)
 * でモジュールをまたぐテーブル結合を禁止している。
 * そこで quiz 側は「回答済みかどうかを教えてくれる何か」だけを知る形にした。
 *
 * answer-service を別プロセスに分離するときは、この実装を HTTP 呼び出しに差し替えるだけで済む。
 * quiz 側のコードは変わらない。
 */
interface AnsweredQuizzes {
    /**
     * 指定ユーザーが回答済みのクイズ ID を返す。
     *
     * 候補を渡して絞り込む形にしているのは、**全回答履歴を取得させないため**。
     * 回答数が増えても、返る件数は候補の数を超えない。
     */
    fun filterAnswered(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID>

    /**
     * 指定ユーザーの**直近の回答が不正解**だったクイズ ID を返す。後で正解したものは含めない。
     * 絞り込みの形は [filterAnswered] と同じ。
     */
    fun filterMissed(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID>

    /**
     * 指定ユーザーが「あとで見直す」印を付けたクイズ ID を返す。絞り込みの形は [filterAnswered] と同じ。
     *
     * 印も回答と同じく、利用者がクイズに残した記録として answer モジュールが持つ。
     */
    fun filterBookmarked(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID>

    /**
     * 指定ユーザーの「今日の復習」のクイズ ID を返す。次に出す日が今日（日本時間）までのもの（DEV-178）。
     * 絞り込みの形は [filterAnswered] と同じ。
     */
    fun filterDue(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID>

    /**
     * クイズごとに、**テナントの全員の**回答の数と正解の数を返す。回答のないクイズは含めない。
     *
     * ほかの関数と違い、利用者で絞らない。管理者が問題の出来を見るためのもの。
     * 挑戦をまたいだ解き直しは、それぞれ 1 回と数える。
     */
    fun tally(quizIds: List<UUID>): Map<UUID, AnswerTally>

    /**
     * クイズ 1 問について、選択肢の ID ごとに、**テナントの全員が**選んだ回数を返す（DEV-170）。
     * いまはない選択肢（消したもの）への回答も含める。選ばれていない選択肢は含めない。
     */
    fun countChoices(quizId: UUID): Map<UUID, Int>
}
