package com.quizapp.answer.usecase

import com.quizapp.answer.domain.BookmarkRepository
import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.auth.UserContext
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 「あとで見直す」印の付け外し（DEV-159）。
 *
 * **付けられるのは、いま出題できるクイズだけ。** 下書きや別のテナントのクイズに付けられると、
 * 印の有無から、見えないはずのクイズがあることが分かってしまう。
 * 外すほうは確かめない。消えたクイズの印も外せるようにする。
 */
@Service
class BookmarkUseCase(
    private val repository: BookmarkRepository,
    private val catalog: QuizCatalog,
    private val tenantTransaction: TenantTransaction,
) {
    /**
     * 印を付けたクイズのうち、いま出題できるもの。消えたクイズへの印は出さない。
     *
     * カテゴリと難易度も返す。出題の設定画面が、選んだ範囲に何問あるかを数えるため。
     * 範囲を変えるたびに問い合わせずに済み、印の数は多くてもテナントのクイズの数にとどまる
     */
    fun list(): BookmarkList = tenantTransaction.execute {
        val quizIds = repository.findQuizIds(UserContext.require())
        val quizzes = catalog.findDeliverable(quizIds.toList())
        BookmarkList(
            quizzes.map { BookmarkedQuiz(it.id, it.categoryId, it.difficultyId) }.sortedBy { it.quizId },
        )
    }

    fun add(quizId: UUID) = tenantTransaction.executeWithoutResult {
        if (catalog.findDeliverable(listOf(quizId)).isEmpty()) throw BookmarkTargetNotFoundException()
        repository.add(UserContext.require(), quizId)
    }

    fun remove(quizId: UUID) = tenantTransaction.executeWithoutResult {
        repository.remove(UserContext.require(), quizId)
    }
}

/** 一覧を配列のまま返さない。項目を足せるようにする */
data class BookmarkList(val items: List<BookmarkedQuiz>)

data class BookmarkedQuiz(val quizId: UUID, val categoryId: UUID, val difficultyId: UUID)

class BookmarkTargetNotFoundException : RuntimeException("印を付けるクイズが見つかりません")
