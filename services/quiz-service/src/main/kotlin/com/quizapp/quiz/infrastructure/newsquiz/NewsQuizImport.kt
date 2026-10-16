package com.quizapp.quiz.infrastructure.newsquiz

import com.quizapp.logging.LogContext
import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import com.quizapp.quiz.usecase.QuizImportRejectedException
import com.quizapp.quiz.usecase.QuizImportRow
import com.quizapp.quiz.usecase.QuizImportUseCase
import com.quizapp.tenant.TenantContext
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * AI が技術ニュースから作った下書きを、受け箱から取り込む（ADR-0031）。定期的に呼ばれる（[NewsQuizConfiguration]）。
 *
 * **利用者の要求で DB を使ってから [NewsQuizProperties.activeWindow] の間にだけ見る。** Outbox の拾い直し、データの整合性の確認と同じ。
 * 決まった時刻に取り込むと、そのたびに一時停止中の Aurora を起こす（min 0 ACU）。
 *
 * - 下書きとして入れる。管理者が確かめるまで出題しない
 * - 取り込みの API と同じユースケースを通す。決まり（選択肢 4 つ、正解 1 つ）と、同じ問題文の重複もそこで確かめる
 * - 確かめていない AI の下書きが [NewsQuizProperties.maxPendingDrafts] 件あれば、取り込まない。受け箱に残す
 * - 読めないもの、決まりを満たさないものは、ログに残して捨てる。何度読んでも取り込めない
 * - 取り込んでから消す。消す前に止まっても、次の回は同じ問題文の重複として捨てる
 */
class NewsQuizImport(
    private val inbox: NewsQuizInbox,
    private val drafts: NewsQuizDrafts,
    private val importer: QuizImportUseCase,
    private val activity: DatabaseActivity,
    private val properties: NewsQuizProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var lastRunAt: Instant? = null

    // S3 と DB の失敗は、ここで記録して終える。前に見た時刻を進めず、次の回にやり直す
    @Suppress("TooGenericExceptionCaught")
    fun tick() {
        val now = clock.instant()
        if (!due(now)) return
        try {
            importInbox()
        } catch (e: RuntimeException) {
            log.warn("技術ニュースの下書きを取り込めませんでした。次の回にやり直します", e)
            return
        }
        lastRunAt = now
    }

    private fun due(now: Instant): Boolean = activity.usedWithin(properties.activeWindow) &&
        lastRunAt.let { it == null || !now.isBefore(it.plus(properties.every)) }

    private fun importInbox() {
        val keys = inbox.keys(properties.maxPendingDrafts)
        val tenantId = if (keys.isEmpty()) null else targetTenant()
        if (tenantId == null) return

        TenantContext.set(tenantId)
        LogContext.putTenant(tenantId)
        try {
            importInto(keys)
        } finally {
            TenantContext.clear()
            LogContext.clear()
        }
    }

    private fun targetTenant(): UUID? = drafts.tenantId(properties.tenantSlug).also {
        if (it == null) log.warn("技術ニュースの下書きを入れる先のテナントがありません: {}", properties.tenantSlug)
    }

    private fun importInto(keys: List<String>) {
        val room = properties.maxPendingDrafts - drafts.pendingCount()
        if (room <= 0) {
            log.info("確かめていない技術ニュースの下書きが {} 件あるため、取り込みません", properties.maxPendingDrafts)
            return
        }
        val results = keys.take(room).map(::importOne)
        log.atInfo()
            .addKeyValue(IMPORTED, results.count { it == Result.IMPORTED })
            .addKeyValue(DISCARDED, results.count { it == Result.DISCARDED })
            .log("技術ニュースの下書きを取り込みました")
    }

    private fun importOne(key: String): Result {
        val json = inbox.read(key) ?: return Result.GONE
        val rejected = try {
            importer.import(listOf(rowOf(json)))
            null
        } catch (e: JacksonException) {
            "読めません: ${e.originalMessage}"
        } catch (e: IllegalArgumentException) {
            e.message ?: "読めません"
        } catch (e: QuizImportRejectedException) {
            // 理由に、送られてきた値は含まれない（QuizImportRowError）
            e.rows.flatMap { it.messages }.joinToString("、")
        }
        inbox.delete(key)
        rejected?.let { log.warn("技術ニュースの下書きを捨てます: {}（{}）", key, it) }
        return if (rejected == null) Result.IMPORTED else Result.DISCARDED
    }

    private fun rowOf(json: String): QuizImportRow = NewsQuizDraft.parse(json).also {
        require(it.source.url.startsWith(properties.sourcePrefix)) { "出典が、読む情報源の外を指しています" }
    }.toRow()

    private fun NewsQuizDraft.toRow() = QuizImportRow(
        category = properties.category,
        difficulty = properties.difficulty,
        question = quiz.question,
        explanation = quiz.explanation,
        choices = quiz.choices.map { QuizImportRow.ChoiceInput(body = it.body, isCorrect = it.isCorrect) },
        status = "draft",
        sourceUrl = source.url,
    )

    private enum class Result { IMPORTED, DISCARDED, GONE }

    companion object {
        const val IMPORTED = "news_quizzes.imported"
        const val DISCARDED = "news_quizzes.discarded"
    }
}
