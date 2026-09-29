package com.quizapp.quiz.infrastructure

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.HttpStatusCode
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.MetadataDirective
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * オブジェクトの置き場所。S3 の API のうち、図に要るものだけを持つ。
 *
 * テストはメモリ上の実装に差し替える。キーの組み立て（[BucketFigureStore]）は、差し替えずに本物を通す。
 */
interface Bucket {
    fun put(key: String, body: ByteArray, contentType: String, cacheControl: String)

    /** 無ければ null */
    fun get(key: String): ByteArray?

    /** 大きさ（バイト）。無ければ null */
    fun size(key: String): Long?

    /** 先頭の [bytes] バイト。本体が短ければ、あるだけ返す。無ければ null */
    fun head(key: String, bytes: Int): ByteArray?

    /** バケットの中で写す。種類とキャッシュの指定は、写した先のものに置き換える */
    fun copy(from: String, to: String, contentType: String, cacheControl: String)

    fun delete(key: String)
}

class S3Bucket(private val client: S3Client, private val name: String) : Bucket {

    override fun put(key: String, body: ByteArray, contentType: String, cacheControl: String) {
        client.putObject(
            { it.bucket(name).key(key).contentType(contentType).cacheControl(cacheControl) },
            RequestBody.fromBytes(body),
        )
    }

    // AWS では、一覧（ListBucket）の権限があるときだけ、無いオブジェクトが 404 になる（無ければ 403）。
    // 上がってくる前の画像（incoming/）を「まだ無い」と見分けるため、アプリのロールに一覧の権限を与えている
    override fun get(key: String): ByteArray? = try {
        client.getObjectAsBytes { it.bucket(name).key(key) }.asByteArray()
    } catch (expected: NoSuchKeyException) {
        null
    }

    override fun head(key: String, bytes: Int): ByteArray? = try {
        client.getObjectAsBytes { it.bucket(name).key(key).range("bytes=0-${bytes - 1}") }.asByteArray()
    } catch (expected: NoSuchKeyException) {
        null
    }

    override fun copy(from: String, to: String, contentType: String, cacheControl: String) {
        client.copyObject {
            it.sourceBucket(name).sourceKey(from).destinationBucket(name).destinationKey(to)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType)
                .cacheControl(cacheControl)
        }
    }

    override fun size(key: String): Long? = try {
        client.headObject { it.bucket(name).key(key) }.contentLength()
    } catch (expected: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        // HEAD は本文がなく、SDK が NoSuchKey に読み替えられないことがある
        if (e.statusCode() == HttpStatusCode.NOT_FOUND) null else throw e
    }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(name).key(key) }
    }
}
