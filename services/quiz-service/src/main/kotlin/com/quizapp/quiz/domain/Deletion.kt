package com.quizapp.quiz.domain

import java.time.Instant
import java.util.UUID

/**
 * 削除したときに巻き込む範囲。削除前に利用者へ提示する。
 *
 * 件数が 0 でも確認を挟む。「配下に何も無いから黙って消す」と、
 * 消えたことに気づく機会がなくなる。
 */
data class DeletionImpact(val difficultyCount: Int = 0, val quizCount: Int = 0)

/** 削除済み一覧の 1 行。 */
data class DeletedItem(
    val id: UUID,
    val name: String,
    /** カテゴリなら null。難易度・クイズは所属を示す */
    val categoryName: String? = null,
    val deletedAt: Instant,
    /** 親が削除済みだと、これだけを復活させられない */
    val restorable: Boolean,
)

data class Trash(
    val categories: List<DeletedItem> = emptyList(),
    val difficulties: List<DeletedItem> = emptyList(),
    val quizzes: List<DeletedItem> = emptyList(),
)

/**
 * 論理削除と復活。**連鎖の対象と順序をここに閉じ込める。**
 *
 * 個々のリポジトリに `softDelete` を置くと、呼び出し側が配下を消して回ることになり、
 * 消し漏れがそのまま「親は消えたのに子が出題される」状態になる（ADR-0007）。
 * 削除の入口をここ 1 つにしている。
 *
 * 「削除済みを除く」条件は自動で差し込まない（[ADR-0009](../../../../../../../../docs/adr/0009-use-spring-data-jdbc.md)）。
 * **削除済みを扱う操作はこのインターフェースにだけ置き、名前で区別する。**
 * 通常の取得メソッドから削除済みが返らないことは、テストで担保する。
 */
interface DeletionRepository {

    fun impactOfCategory(categoryId: UUID): DeletionImpact?

    fun impactOfDifficulty(categoryId: UUID, difficultyId: UUID): DeletionImpact?

    /** カテゴリと、配下の難易度・クイズをまとめて削除する。見つからなければ false。 */
    fun deleteCategory(categoryId: UUID): Boolean

    /** 難易度と、それを使うクイズを削除する。 */
    fun deleteDifficulty(difficultyId: UUID): Boolean

    fun deleteQuiz(quizId: UUID): Boolean

    fun listDeleted(): Trash

    /**
     * 復活させる。**同じ操作で消えたものだけを一緒に戻す。**
     *
     * 削除したあとに一部だけ個別に復活させ、そのあと親を復活させる、という順序でも
     * すでに戻っている行には触れない。
     *
     * 復活できなければ [RestoreBlocked] を投げる。
     */
    fun restoreCategory(categoryId: UUID)

    fun restoreDifficulty(difficultyId: UUID)

    fun restoreQuiz(quizId: UUID)
}

/** 復活できない理由。 */
enum class RestoreObstacle {
    /** 親が削除済み。先に親を戻す必要がある */
    PARENT_DELETED,

    /** 同じ名前のものが作り直されている */
    NAME_TAKEN,
}

class RestoreBlocked(val obstacle: RestoreObstacle, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class DeletedItemNotFound(val id: UUID) : RuntimeException("削除済みの項目が見つかりません: $id")
