package com.quizapp.quiz.infrastructure

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * オブジェクトの置き場所。S3 の API のうち、図に要るものだけを持つ。
 *
 * テストはメモリ上の実装に差し替える。キーの組み立て（[BucketFigureStore]）は、差し替えずに本物を通す。
 */
interface Bucket {
    fun put(key: String, body: ByteArray, contentType: String, cacheControl: String)

    /** 無ければ null */
    fun get(key: String): ByteArray?

    fun delete(key: String)
}

class S3Bucket(private val client: S3Client, private val name: String) : Bucket {

    override fun put(key: String, body: ByteArray, contentType: String, cacheControl: String) {
        client.putObject(
            { it.bucket(name).key(key).contentType(contentType).cacheControl(cacheControl) },
            RequestBody.fromBytes(body),
        )
    }

    // 一覧（ListBucket）の権限を持たないため、AWS では無いオブジェクトも 403 で返る。
    // 図は行があるときだけ読むので、ここで 403 になるのは行と本体がずれたとき。null にせず失敗させる
    override fun get(key: String): ByteArray? = try {
        client.getObjectAsBytes { it.bucket(name).key(key) }.asByteArray()
    } catch (expected: NoSuchKeyException) {
        null
    }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(name).key(key) }
    }
}
