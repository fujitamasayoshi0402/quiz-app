package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.FigureContent
import com.quizapp.quiz.domain.FigureImage
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.FigureStore
import com.quizapp.quiz.domain.ImageFormat
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
 *
 * 画像（ADR-0020）は、ブラウザが検査の前の置き場所へ直接上げる。完了を受けたら、検査して読み直したものを置き、行を入れる。
 * 上がってきたものは、検査に通っても通らなくても消す。
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
        tenantTransaction.executeWithoutResult { repository.add(id, FigureKind.DRAWIO) }
        return id
    }

    /** 画像を上げる準備。図の ID を決め、ブラウザが本体を上げる URL を返す。行はまだ入れない */
    fun startUpload(contentType: String, size: Long): FigureUpload {
        val format = requireNotNull(ImageFormat.fromContentType(contentType)) { FigureImage.UNSUPPORTED }
        require(size in 1..FigureImage.MAX_BYTES) { FigureImage.TOO_LARGE }
        val id = UUID.randomUUID()
        return FigureUpload(id, store.uploadUrl(id, format, size), format.contentType)
    }

    /**
     * 上がってきた画像を検査し、読み直したものを置いてから行を入れる。
     * 大きさは、中身を読む前に確かめる。署名で大きさを縛っているが、ここでも信じない
     */
    fun completeUpload(id: UUID): UUID {
        val size = store.uploadSize(id) ?: throw FigureUploadNotFoundException(id)
        try {
            require(size <= FigureImage.MAX_BYTES) { FigureImage.TOO_LARGE }
            val uploaded = store.readUpload(id) ?: throw FigureUploadNotFoundException(id)
            store.saveImage(id, FigureImage.from(uploaded))
            tenantTransaction.executeWithoutResult { repository.add(id, FigureKind.IMAGE) }
        } finally {
            runCatching { store.deleteUpload(id) }
                .onFailure { log.warn("上がってきた画像を消せませんでした。置き場所のライフサイクルが消します: {}", id, it) }
        }
        return id
    }

    fun kind(id: UUID): FigureKind = tenantTransaction.execute {
        repository.findKind(id) ?: throw FigureNotFoundException(id)
    }

    /** 描き直すための原本。draw.io の図にだけある */
    fun source(id: UUID): String {
        if (kind(id) != FigureKind.DRAWIO) throw FigureNotFoundException(id)
        return store.findSource(id) ?: throw FigureNotFoundException(id)
    }

    fun url(id: UUID): URI = store.url(id, kind(id))

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
}

/** 画像を上げる先。ブラウザは [url] へ、[contentType] を付けて PUT する */
data class FigureUpload(val id: UUID, val url: URI, val contentType: String)

class FigureNotFoundException(val id: UUID) : RuntimeException("図が見つかりません: $id")

class FigureUploadNotFoundException(val id: UUID) : RuntimeException("上がってきた画像が見つかりません: $id")
