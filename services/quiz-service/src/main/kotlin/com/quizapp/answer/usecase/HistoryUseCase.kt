package com.quizapp.answer.usecase

import com.quizapp.answer.domain.AttemptCursor
import com.quizapp.answer.domain.AttemptHistoryQuery
import com.quizapp.answer.domain.CompletedAttempt
import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.auth.UserContext
import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 自分の過去の挑戦と、カテゴリ別の正答率。
 *
 * **見られるのは本人の記録だけ。** 利用者はリクエストからではなく [UserContext] から取る。
 * 管理者が利用者ごとの成績を見る画面は、まだ作らない。
 */
@Service
class HistoryUseCase(
    private val history: AttemptHistoryQuery,
    private val catalog: QuizCatalog,
    private val tenantTransaction: TenantTransaction,
) {
    /**
     * 完了した挑戦を新しい順に返す。途中でやめた挑戦は出さない（挑戦単位の集計は完了したものだけ）。
     *
     * 1 件多く読み、続きがあるかを知る。件数を数える SQL を別に流さずに済む。
     */
    fun attempts(after: AttemptCursor?): AttemptHistoryPage = tenantTransaction.execute {
        val rows = history.findCompleted(UserContext.require(), after, PAGE_SIZE + 1)
        val page = rows.take(PAGE_SIZE)
        val names = names(catalog.findPlayableCategories())

        AttemptHistoryPage(
            items = page.map { it.toItem(names) },
            nextCursor = if (rows.size > PAGE_SIZE) page.last().cursor.encode() else null,
        )
    }

    /**
     * カテゴリ別の正答率。**いま出題できるクイズへの、最新の回答だけで数える。**
     *
     * - 解き直して正解すれば上がる。「いまわかっているか」を表すため、過去の誤りを残し続けない
     * - 削除・非公開にしたクイズへの回答は数えない。誤りがあって消した問題が率に残らず、
     *   カテゴリの構成もいまの出題と一致する
     *
     * 回答していないカテゴリも返す。どこに手を付けていないかも履歴の一部である。
     */
    fun categories(): List<CategoryScore> = tenantTransaction.execute {
        val latest = history.findLatestResults(UserContext.require())
        val categoryOf = catalog.findCategoryIds(latest.keys)
        val results = latest.entries
            .mapNotNull { (quizId, isCorrect) -> categoryOf[quizId]?.let { it to isCorrect } }
            .groupBy({ it.first }, { it.second })

        catalog.findPlayableCategories().map { category ->
            val answered = results[category.id].orEmpty()
            CategoryScore(
                categoryId = category.id,
                name = category.name,
                quizCount = category.quizCount,
                answeredCount = answered.size,
                correctCount = answered.count { it },
            )
        }
    }

    /** カテゴリと難易度の名前。ID が重ならないので 1 つにまとめる */
    private fun names(categories: List<PlayableCategory>): Map<UUID, String> =
        categories.associate { it.id to it.name } +
            categories.flatMap { it.difficulties }.associate { it.id to it.name }

    private fun CompletedAttempt.toItem(names: Map<UUID, String>) = AttemptHistoryItem(
        id = id,
        categoryId = categoryId,
        categoryName = categoryId?.let(names::get),
        difficultyId = difficultyId,
        difficultyName = difficultyId?.let(names::get),
        level = level,
        scope = scope.name.lowercase(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        totalCount = totalCount,
        answeredCount = answeredCount,
        correctCount = correctCount,
    )

    companion object {
        const val PAGE_SIZE = 20
    }
}

/** [nextCursor] が null なら、これが最後のページ。 */
data class AttemptHistoryPage(val items: List<AttemptHistoryItem>, val nextCursor: String?)

/**
 * 完了した挑戦 1 件。
 *
 * [categoryId] が null なら、カテゴリを絞らずに解いた挑戦。
 * [categoryId] があるのに [categoryName] が null なら、そのカテゴリはいま出題されていない（削除・非公開）。
 * 難易度も同じ。名前を記録していないので、消えたものの名前は出せない。
 */
data class AttemptHistoryItem(
    val id: UUID,
    val categoryId: UUID?,
    val categoryName: String?,
    val difficultyId: UUID?,
    val difficultyName: String?,
    val level: Int?,
    val scope: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val totalCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
)

/**
 * カテゴリ 1 つの成績。正答率は [correctCount] / [answeredCount]。
 *
 * [quizCount] は出題できるクイズの数で、[answeredCount] はそのうち解いたものの数。
 * 率をサーバーで丸めて返さない。表示の桁はクライアントが決める。
 */
data class CategoryScore(
    val categoryId: UUID,
    val name: String,
    val quizCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
)
