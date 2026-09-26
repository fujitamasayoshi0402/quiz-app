package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.FigureContent
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.FigureStore
import com.quizapp.tenant.TenantTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID

/**
 * 解説図を置き、取り出す（ADR-0017）。
 *
 * 本体（S3）と行（DB）は同じトランザクションに入らない。**どちらかだけが残るときは、本体だけが残る順にする。**
 * 行がなければ誰にも返さないため、残った本体は見えない。逆に行だけが残ると、ない図を指す URL を返してしまう。
 *
 * S3 を呼んでいる間は、DB のトランザクションを開かない。
 */
@Service
class FigureUseCase(
    private val repository: FigureRepository,
    private val store: FigureStore,
    private val tenantTransaction: TenantTransaction,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 本体を置いてから、行を入れる。ID は推測できない乱数にする。出題の時点で図を見せないことは、ID を渡さないことで守る */
    fun create(source: String, svg: String): UUID {
        val content = FigureContent(source, svg)
        val id = UUID.randomUUID()
        store.save(id, content)
        tenantTransaction.executeWithoutResult { repository.add(id) }
        return id
    }

    fun source(id: UUID): String {
        requireVisible(id)
        return store.findSource(id) ?: throw FigureNotFoundException(id)
    }

    fun svgUrl(id: UUID): URI {
        requireVisible(id)
        return store.svgUrl(id)
    }

    /**
     * 行を消してから、本体を消す。本体を消せなくても失敗にはしない。行がないので、もう誰にも返らない。
     * 発行済みの URL は期限（最長 10 分）まで使える
     */
    fun delete(id: UUID) {
        tenantTransaction.executeWithoutResult {
            if (!repository.delete(id)) throw FigureNotFoundException(id)
        }
        runCatching { store.delete(id) }
            .onFailure { log.warn("図の行は消しましたが、本体を消せませんでした: {}", id, it) }
    }

    private fun requireVisible(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!repository.exists(id)) throw FigureNotFoundException(id)
    }
}

class FigureNotFoundException(val id: UUID) : RuntimeException("図が見つかりません: $id")
