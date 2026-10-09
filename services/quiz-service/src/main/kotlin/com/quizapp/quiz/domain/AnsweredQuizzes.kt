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
}
