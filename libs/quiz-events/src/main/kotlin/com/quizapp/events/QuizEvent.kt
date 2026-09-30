package com.quizapp.events

import java.time.Instant
import java.util.UUID

/**
 * quiz-service が送るイベント（ADR-0022）。EventBridge の `detail` に入る。
 *
 * **正解、選択肢、解説は載せない。** バス、アーカイブ、ログは quiz-service の外にある。
 * 載せるのは、どのクイズかが分かることと、画面へのリンクを作れることまで。
 *
 * 項目を足すだけなら [version] を上げない。受け手は知らない項目を無視する。
 * 消す・意味を変えるときは版を上げ、受け手が新旧どちらも読めるようにしてから、送る側を替える。
 */
sealed interface QuizEvent {
    /** Outbox の行の ID。何度送り直しても変わらないため、受け手が重複を捨てる鍵にする */
    val eventId: UUID
    val version: Int
    val occurredAt: Instant
    val tenant: TenantRef

    companion object {
        /** EventBridge の `source` */
        const val SOURCE = "quiz-app.quiz-service"
    }
}

/**
 * EventBridge の `detail-type`。受け手のルールがこの名前で絞り込む。
 *
 * クラスの名前から作らず、ここで書き下す。クラスの名前を変えただけで、送る名前が黙って変わらないようにする
 */
val QuizEvent.detailType: String
    get() = when (this) {
        is QuizCreated -> "QuizCreated"
        is QuizUpdated -> "QuizUpdated"
        is QuizPublished -> "QuizPublished"
        is QuizUnpublished -> "QuizUnpublished"
        is QuizzesImported -> "QuizzesImported"
    }

/** クイズを作った。下書きでも公開でも送る。どちらかは [QuizRef.status] で分かる */
data class QuizCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val tenant: TenantRef,
    val quiz: QuizRef,
    override val version: Int = 1,
) : QuizEvent

/** 状態を変えずに、中身を変えた。状態が変わった保存は [QuizPublished] / [QuizUnpublished] だけを送る */
data class QuizUpdated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val tenant: TenantRef,
    val quiz: QuizRef,
    override val version: Int = 1,
) : QuizEvent

/** 下書きを公開した */
data class QuizPublished(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val tenant: TenantRef,
    val quiz: QuizRef,
    override val version: Int = 1,
) : QuizEvent

/** 公開を下書きに戻した */
data class QuizUnpublished(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val tenant: TenantRef,
    val quiz: QuizRef,
    override val version: Int = 1,
) : QuizEvent

/** 一括インポートで取り込んだ。1 件ずつは送らず、件数だけを 1 つにまとめる */
data class QuizzesImported(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val tenant: TenantRef,
    val imported: ImportedCount,
    override val version: Int = 1,
) : QuizEvent

data class TenantRef(val id: UUID, val slug: String, val name: String)

data class QuizRef(
    val id: UUID,
    val status: QuizEventStatus,
    val category: CategoryRef,
    /** 問題文の冒頭。[QUESTION_EXCERPT_LENGTH] 文字まで */
    val question: String,
) {
    companion object {
        const val QUESTION_EXCERPT_LENGTH = 100
    }
}

data class CategoryRef(val id: UUID, val name: String)

/**
 * 取り込んだ件数。[published] は、そのうち公開の状態で取り込んだもの。
 * 受け手は、下書きだけの取り込みを通知しないために使う（公開中のクイズに関わるものだけを通知する）
 */
data class ImportedCount(val total: Int, val published: Int)

enum class QuizEventStatus {
    DRAFT,
    PUBLISHED,
}
