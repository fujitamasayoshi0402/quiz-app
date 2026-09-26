package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.FigureContent
import com.quizapp.quiz.domain.FigureStore
import com.quizapp.tenant.TenantContext
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

/**
 * 図の本体をバケットに置く（ADR-0017）。
 *
 * **キーにテナントを含める。** テナントは要求の文脈から取り、図の ID だけを受け取る。
 * 行の確かめ（[com.quizapp.quiz.domain.FigureRepository]）をすり抜けても、キーは自テナントの下しか指さない。
 *
 * | キー                                   | 読む者                               |
 * | -------------------------------------- | ------------------------------------ |
 * | `svg/{テナントの ID}/{図の ID}.svg`       | CloudFront（署名付き URL）            |
 * | `drawio/{テナントの ID}/{図の ID}.drawio` | quiz-service だけ。管理者に API で返す |
 */
@Component
class BucketFigureStore(private val bucket: Bucket, private val signer: FigureUrlSigner) : FigureStore {

    override fun save(id: UUID, content: FigureContent) {
        val tenantId = TenantContext.require()
        bucket.put(sourceKey(tenantId, id), content.source.toByteArray(), SOURCE_TYPE, NO_CACHE)
        bucket.put(svgKey(tenantId, id), content.svg.toByteArray(), SVG_TYPE, IMMUTABLE)
    }

    override fun findSource(id: UUID): String? = bucket.get(sourceKey(TenantContext.require(), id))?.decodeToString()

    override fun delete(id: UUID) {
        val tenantId = TenantContext.require()
        bucket.delete(svgKey(tenantId, id))
        bucket.delete(sourceKey(tenantId, id))
    }

    override fun svgUrl(id: UUID): URI = signer.sign(svgKey(TenantContext.require(), id))

    companion object {
        const val SVG_TYPE = "image/svg+xml"
        const val SOURCE_TYPE = "application/xml"

        /** 図は変えないので、1 年持たせてよい。CloudFront もこれに従う */
        const val IMMUTABLE = "max-age=31536000, immutable"

        /** 原本は API を通して返すだけで、キャッシュに置かせない */
        const val NO_CACHE = "no-store"

        fun svgKey(tenantId: UUID, id: UUID) = "svg/$tenantId/$id.svg"

        fun sourceKey(tenantId: UUID, id: UUID) = "drawio/$tenantId/$id.drawio"
    }
}
