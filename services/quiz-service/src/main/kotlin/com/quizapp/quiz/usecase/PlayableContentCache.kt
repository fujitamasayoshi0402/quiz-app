package com.quizapp.quiz.usecase

import com.quizapp.cache.ReadCaches
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableCategoryQuery
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 出題の画面が読む、公開した内容（ADR-0038）。DB が止まっていても、カテゴリの一覧と解説図をすぐ返す。
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
    tenantTransaction: TenantTransaction,
    caches: ReadCaches,
) {
    private val categories = caches.refreshing<UUID, List<PlayableCategory>> { tenantId ->
        TenantContext.within(tenantId) { tenantTransaction.execute { query.findAll() } }
    }

    private val figureKinds = caches.refreshing<Pair<UUID, UUID>, FigureKind> { (tenantId, figureId) ->
        TenantContext.within(tenantId) { tenantTransaction.executeNullable { figures.findKind(figureId) } }
    }

    fun categories(): List<PlayableCategory> = categories.get(TenantContext.require()).orEmpty()

    /** 図がなければ null */
    fun figureKind(figureId: UUID): FigureKind? = figureKinds.get(TenantContext.require() to figureId)

    fun forget(tenantId: UUID) {
        categories.forget(tenantId)
        figureKinds.forgetIf { it.first == tenantId }
    }
}
