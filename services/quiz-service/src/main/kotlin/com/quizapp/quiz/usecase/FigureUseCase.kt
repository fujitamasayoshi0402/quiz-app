package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.FigureContent
import com.quizapp.quiz.domain.FigureImage
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigurePdf
import com.quizapp.quiz.domain.FigureQuiz
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.FigureStore
import com.quizapp.quiz.domain.FigureUploadStore
import com.quizapp.quiz.domain.FigureUploads
import com.quizapp.quiz.domain.FigureUsages
import com.quizapp.quiz.domain.ImageFormat
import com.quizapp.quiz.domain.LimitedResource
import com.quizapp.quiz.domain.TenantCapacity
import com.quizapp.tenant.TenantTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * 解説図を置き、取り出す（ADR-0017）。
 *
 * 本体（S3）と行（DB）は同じトランザクションに入らない。**どちらかだけが残るときは、本体だけが残る順にする。**
 * 行がなければ誰にも返さないため、残った本体は見えない。逆に行だけが残ると、ない図を指す URL を返してしまう。
 *
 * S3 を呼んでいる間は、DB のトランザクションを開かない。
 *
 * 画像と PDF（ADR-0020）は、ブラウザが検査の前の置き場所へ直接上げる。完了を受けたら、中身で種類を決めて置き、行を入れる。
 * 画像は読み直し、PDF はそのまま写す。上がってきたものは、検査に通っても通らなくても消す。
 * PDF は、本文の中に出すため、1 ページ目を画像にして置く（ADR-0021）。
 */
@Service
class FigureUseCase(
    private val repository: FigureRepository,
    private val store: FigureStore,
    private val uploads: FigureUploadStore,
    private val usages: FigureUsages,
    private val capacity: TenantCapacity,
    private val tenantTransaction: TenantTransaction,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 本体を置いてから、行を入れる。ID は推測できない乱数にする。出題の時点で図を見せないことは、ID を渡さないことで守る */
    fun create(source: String, svg: String): UUID {
        requireRoomForFigure()
        val content = FigureContent(source, svg)
        val id = UUID.randomUUID()
        store.save(id, content)
        tenantTransaction.executeWithoutResult { repository.add(id, FigureKind.DRAWIO) }
        return id
    }

    /** 画像か PDF を上げる準備。図の ID を決め、ブラウザが本体を上げる URL を返す。行はまだ入れない */
    fun startUpload(contentType: String, size: Long): FigureUpload {
        val (maxBytes, tooLarge) = requireNotNull(FigureUploads.limitFor(contentType)) { FigureUploads.UNSUPPORTED }
        require(size in 1..maxBytes) { tooLarge }
        // 上げてから断ると、ブラウザが送った本体が無駄になる。完了のときにも確かめる
        requireRoomForFigure()
        val id = UUID.randomUUID()
        return FigureUpload(id, uploads.uploadUrl(id, contentType, size), contentType)
    }

    /**
     * 上がってきたファイルを、中身で種類を決めて置いてから行を入れる。
     * 大きさは、中身を読む前に確かめる。署名で大きさを縛っているが、ここでも信じない
     */
    fun completeUpload(id: UUID): FigureKind {
        val size = uploads.uploadSize(id) ?: throw FigureUploadNotFoundException(id)
        try {
            requireRoomForFigure()
            val kind = place(id, size, head(id))
            tenantTransaction.executeWithoutResult { repository.add(id, kind) }
            return kind
        } finally {
            runCatching { uploads.deleteUpload(id) }
                .onFailure { log.warn("上がってきたファイルを消せませんでした。置き場所のライフサイクルが消します: {}", id, it) }
        }
    }

    /**
     * 申告された種類ではなく、先頭のバイトで決める。画像は読み直す。
     * PDF は 1 ページ目を画像にしてから、本体をそのまま写す。画像にできない PDF は、写す前に断る
     */
    private fun place(id: UUID, size: Long, head: ByteArray): FigureKind = when {
        FigurePdf.matches(head) -> {
            require(size <= FigurePdf.MAX_BYTES) { FigurePdf.TOO_LARGE }
            store.saveImage(id, FigurePdf.preview(readUpload(id)))
            uploads.promotePdf(id)
            FigureKind.PDF
        }

        ImageFormat.detect(head) != null -> {
            require(size <= FigureImage.MAX_BYTES) { FigureImage.TOO_LARGE }
            store.saveImage(id, FigureImage.from(readUpload(id)))
            FigureKind.IMAGE
        }

        else -> throw IllegalArgumentException(FigureUploads.UNSUPPORTED)
    }

    /** 本体を置く前に確かめる。上限を超えて断ったときに、置いた本体が残らない（ADR-0028） */
    private fun requireRoomForFigure() =
        tenantTransaction.executeWithoutResult { capacity.requireRoomFor(LimitedResource.FIGURES) }

    private fun head(id: UUID): ByteArray =
        uploads.readUploadHead(id, FigureUploads.HEAD_BYTES) ?: throw FigureUploadNotFoundException(id)

    private fun readUpload(id: UUID): ByteArray = uploads.readUpload(id) ?: throw FigureUploadNotFoundException(id)

    fun kind(id: UUID): FigureKind = tenantTransaction.execute {
        repository.findKind(id) ?: throw FigureNotFoundException(id)
    }

    /** 描き直すための原本。draw.io の図にだけある */
    fun source(id: UUID): String {
        if (kind(id) != FigureKind.DRAWIO) throw FigureNotFoundException(id)
        return store.findSource(id) ?: throw FigureNotFoundException(id)
    }

    fun url(id: UUID): URI = store.url(id, kind(id))

    /** 本文の中に出す画像の URL。PDF は 1 ページ目の画像になる */
    fun previewUrl(id: UUID): URI = store.previewUrl(id, kind(id))

    /** このテナントの図と、それを使っている問題の数。置いた日時の新しい順（DEV-165） */
    fun list(): List<FigureSummary> = tenantTransaction.execute {
        val used = usages.usages()
        repository.list().map {
            val usage = used[it.id]
            FigureSummary(it.id, it.kind, it.createdAt, usage?.live.orEmpty(), usage?.trashed.orEmpty())
        }
    }

    /**
     * 行を消してから、本体を消す。本体を消せなくても失敗にはしない。行がないので、もう誰にも返らない。
     * 発行済みの URL は期限（最長 10 分）まで使える。
     *
     * **公開中・下書きの問題が解説で使っている図は消さない**（DEV-165）。消すと、解説がない図を指す。
     * ゴミ箱の問題だけが使っている図は消せる。その問題は、ゴミ箱から戻せなくなる（[com.quizapp.quiz.domain.RestoreObstacle.MISSING_FIGURE]）
     */
    fun delete(id: UUID) {
        tenantTransaction.executeWithoutResult {
            val inUse = usages.usages()[id]?.live.orEmpty()
            if (inUse.isNotEmpty()) throw FigureInUseException(id, inUse.map(FigureQuiz::question))
            if (!repository.delete(id)) throw FigureNotFoundException(id)
        }
        runCatching { store.delete(id) }
            .onFailure { log.warn("図の行は消しましたが、本体を消せませんでした: {}", id, it) }
    }
}

/** ファイルを上げる先。ブラウザは [url] へ、[contentType] を付けて PUT する */
data class FigureUpload(val id: UUID, val url: URI, val contentType: String)

class FigureNotFoundException(val id: UUID) : RuntimeException("図が見つかりません: $id")

/** 公開中・下書きの問題が使っているため、消せない。[questions] は使っている問題の問題文 */
class FigureInUseException(val id: UUID, val questions: List<String>) : RuntimeException("図が使われています: $id")

/** 一覧に出す図。使っている問題は、生きているもの（公開中・下書き）とゴミ箱のものに分ける */
data class FigureSummary(
    val id: UUID,
    val kind: FigureKind,
    val createdAt: Instant,
    val quizzes: List<FigureQuiz>,
    val trashedQuizzes: List<FigureQuiz>,
)

class FigureUploadNotFoundException(val id: UUID) : RuntimeException("上がってきたファイルが見つかりません: $id")
