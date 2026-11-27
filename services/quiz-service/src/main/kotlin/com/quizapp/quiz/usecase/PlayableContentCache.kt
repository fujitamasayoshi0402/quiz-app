package com.quizapp.quiz.usecase

import com.quizapp.cache.ReadCaches
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableCategoryQuery
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 出題の画面が読む、公開した内容（ADR-0038）。DB が止まっていても、カテゴリの一覧と解説図をすぐ返す。
 * 「始める」で出す問題の候補も持つ（DEV-240）。
 *
 * **テナントごとに持つ。** DB では行レベルセキュリティがテナントを分けるが、メモリには効かない。
 * 鍵に必ずテナントを含め、テナントを決めずに引ける形にしない。
 *
 * 内容を変えるのは管理の API だけで、変えたら [forget] で、そのテナントの分をまとめて消す
 * （[com.quizapp.quiz.controller.ContentChangeInterceptor]）。念のため、数分ごとに裏で読み直す。
 * 裏での読み直しは要求の外で動くため、テナントを設定してから読む
 */
@Component
class PlayableContentCache(
    query: PlayableCategoryQuery,
    figures: FigureRepository,
    quizzes: QuizRepository,
    tenantTransaction: TenantTransaction,
    caches: ReadCaches,
) {
    private val categories = caches.refreshing<UUID, List<PlayableCategory>> { tenantId ->
        TenantContext.within(tenantId) { tenantTransaction.execute { query.findAll() } }
    }

    private val figureKinds = caches.refreshing<Pair<UUID, UUID>, FigureKind> { (tenantId, figureId) ->
        TenantContext.within(tenantId) { tenantTransaction.executeNullable { figures.findKind(figureId) } }
    }

    private val candidates = caches.refreshing<CandidateKey, List<Quiz>> { key ->
        TenantContext.within(key.tenantId) {
            tenantTransaction.execute {
                quizzes.findPublishedCandidates(key.categoryId, key.difficultyId, key.level)
            }
        }
    }

    /** 止めると（テスト）、毎回 DB を読む。そのときは「始める」も DB で行う */
    val enabled: Boolean = caches.enabled

    fun categories(): List<PlayableCategory> = categories.get(TenantContext.require()).orEmpty()

    /** 出題できる公開クイズ。[QuizRepository.findPublishedCandidates] と同じ並び（DEV-240） */
    fun candidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz> =
        candidates.get(CandidateKey(TenantContext.require(), categoryId, difficultyId, level)).orEmpty()

    /** 図がなければ null */
    fun figureKind(figureId: UUID): FigureKind? = figureKinds.get(TenantContext.require() to figureId)

    fun forget(tenantId: UUID) {
        categories.forget(tenantId)
        figureKinds.forgetIf { it.first == tenantId }
        candidates.forgetIf { it.tenantId == tenantId }
    }

    private data class CandidateKey(val tenantId: UUID, val categoryId: UUID?, val difficultyId: UUID?, val level: Int?)
}
