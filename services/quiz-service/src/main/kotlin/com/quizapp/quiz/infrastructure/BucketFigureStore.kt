package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.FigureContent
import com.quizapp.quiz.domain.FigureImage
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigurePdf
import com.quizapp.quiz.domain.FigureStore
import com.quizapp.quiz.domain.FigureUploadStore
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
 * | `img/{テナントの ID}/{図の ID}`           | CloudFront（署名付き URL）            |
 * | `pdf/{テナントの ID}/{図の ID}`           | CloudFront（署名付き URL）            |
 * | `incoming/{テナントの ID}/{図の ID}`      | ブラウザが上げ、quiz-service が検査する。誰にも配らない |
 *
 * 画像と PDF のキーに拡張子は付けない。形式はオブジェクトの Content-Type が持ち、配るときもそれを返す。
 */
@Component
class BucketFigureStore(
    private val bucket: Bucket,
    private val signer: FigureUrlSigner,
    private val uploadSigner: UploadUrlSigner,
) : FigureStore,
    FigureUploadStore {

    override fun save(id: UUID, content: FigureContent) {
        val tenantId = TenantContext.require()
        bucket.put(sourceKey(tenantId, id), content.source.toByteArray(), SOURCE_TYPE, NO_CACHE)
        bucket.put(svgKey(tenantId, id), content.svg.toByteArray(), SVG_TYPE, IMMUTABLE)
    }

    override fun saveImage(id: UUID, image: FigureImage) {
        bucket.put(imageKey(TenantContext.require(), id), image.bytes, image.format.contentType, IMMUTABLE)
    }

    override fun findSource(id: UUID): String? = bucket.get(sourceKey(TenantContext.require(), id))?.decodeToString()

    override fun delete(id: UUID) {
        val tenantId = TenantContext.require()
        bucket.delete(svgKey(tenantId, id))
        bucket.delete(sourceKey(tenantId, id))
        bucket.delete(imageKey(tenantId, id))
        bucket.delete(pdfKey(tenantId, id))
    }

    override fun url(id: UUID, kind: FigureKind): URI {
        val tenantId = TenantContext.require()
        return signer.sign(
            when (kind) {
                FigureKind.DRAWIO -> svgKey(tenantId, id)
                FigureKind.IMAGE -> imageKey(tenantId, id)
                FigureKind.PDF -> pdfKey(tenantId, id)
            },
        )
    }

    override fun uploadUrl(id: UUID, contentType: String, size: Long): URI =
        uploadSigner.sign(uploadKey(TenantContext.require(), id), contentType, size)

    override fun uploadSize(id: UUID): Long? = bucket.size(uploadKey(TenantContext.require(), id))

    override fun readUploadHead(id: UUID, bytes: Int): ByteArray? =
        bucket.head(uploadKey(TenantContext.require(), id), bytes)

    override fun readUpload(id: UUID): ByteArray? = bucket.get(uploadKey(TenantContext.require(), id))

    override fun promotePdf(id: UUID) {
        val tenantId = TenantContext.require()
        bucket.copy(uploadKey(tenantId, id), pdfKey(tenantId, id), FigurePdf.CONTENT_TYPE, IMMUTABLE)
    }

    override fun deleteUpload(id: UUID) {
        bucket.delete(uploadKey(TenantContext.require(), id))
    }

    companion object {
        const val SVG_TYPE = "image/svg+xml"
        const val SOURCE_TYPE = "application/xml"

        /** 図は変えないので、1 年持たせてよい。CloudFront もこれに従う */
        const val IMMUTABLE = "max-age=31536000, immutable"

        /** 原本は API を通して返すだけで、キャッシュに置かせない */
        const val NO_CACHE = "no-store"

        fun svgKey(tenantId: UUID, id: UUID) = "svg/$tenantId/$id.svg"

        fun sourceKey(tenantId: UUID, id: UUID) = "drawio/$tenantId/$id.drawio"

        fun imageKey(tenantId: UUID, id: UUID) = "img/$tenantId/$id"

        fun pdfKey(tenantId: UUID, id: UUID) = "pdf/$tenantId/$id"

        fun uploadKey(tenantId: UUID, id: UUID) = "incoming/$tenantId/$id"
    }
}
