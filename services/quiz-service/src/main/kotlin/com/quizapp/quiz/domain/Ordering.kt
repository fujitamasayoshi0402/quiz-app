package com.quizapp.quiz.domain

import java.util.UUID

/**
 * カテゴリと難易度の並び順（DEV-71）。
 *
 * **並び順は、並べ替えの操作だけで決める。** 作ったものは末尾に置き、名前などを直しても並び順は変えない。
 * 数値を入力させると、ほかの項目の数値も考えて入れ直すことになる。
 */
object Ordering {
    /** 末尾に置くときの並び順。今ある最大の次。何もなければ 0 */
    fun next(sortOrders: Collection<Int>): Int = (sortOrders.maxOrNull() ?: -1) + 1

    /**
     * 並べ替えの指定が、今ある項目をちょうど 1 回ずつ含むこと。
     * 画面を開いている間に項目が増えたり消えたりしていれば、合わない。一部だけを並べ替えると並びが崩れるため、受け付けない
     */
    fun requireSameItems(requested: List<UUID>, current: Collection<UUID>) {
        if (requested.size != current.size || requested.toSet() != current.toSet()) throw OrderOutdatedException()
    }
}

class OrderOutdatedException : RuntimeException("並べ替えの指定が、今ある項目と合いません")
