package com.quizapp.quiz.infrastructure.newsquiz

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * Lambda（news-quiz-drafter）が下書きを置く受け箱（ADR-0031）。1 つのファイルに 1 問。
 *
 * テストはメモリ上の実装に差し替える
 */
interface NewsQuizInbox {
    /** 置かれた順（キーの順）に、最大 [limit] 件 */
    fun keys(limit: Int): List<String>

    /** 無ければ null */
    fun read(key: String): String?

    fun delete(key: String)
}

/** アプリのロールが持つのは、`inbox/` の一覧・読み・消しだけ（Terraform） */
class S3NewsQuizInbox(private val client: S3Client, private val bucket: String) : NewsQuizInbox {

    override fun keys(limit: Int): List<String> = client.listObjectsV2 {
        it.bucket(bucket).prefix(PREFIX).maxKeys(limit)
    }.contents().map { it.key() }.filter { it.endsWith(".json") }

    override fun read(key: String): String? = try {
        client.getObjectAsBytes { it.bucket(bucket).key(key) }.asUtf8String()
    } catch (expected: NoSuchKeyException) {
        null
    }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(key) }
    }

    companion object {
        const val PREFIX = "inbox/"
    }
}
