package com.quizapp.support.fake

import com.quizapp.quiz.infrastructure.Bucket
import java.util.concurrent.ConcurrentHashMap

/** S3 の代わり。置いたものをキーごとに持ち、テストから中身を確かめられる */
class InMemoryBucket : Bucket {

    data class StoredObject(val body: ByteArray, val contentType: String, val cacheControl: String)

    private val objects = ConcurrentHashMap<String, StoredObject>()

    override fun put(key: String, body: ByteArray, contentType: String, cacheControl: String) {
        objects[key] = StoredObject(body, contentType, cacheControl)
    }

    override fun get(key: String): ByteArray? = objects[key]?.body

    override fun size(key: String): Long? = objects[key]?.body?.size?.toLong()

    override fun head(key: String, bytes: Int): ByteArray? = objects[key]?.body?.let {
        it.copyOf(minOf(bytes, it.size))
    }

    override fun copy(from: String, to: String, contentType: String, cacheControl: String) {
        val source = requireNotNull(objects[from]) { "写す元がありません: $from" }
        objects[to] = StoredObject(source.body, contentType, cacheControl)
    }

    override fun delete(key: String) {
        objects.remove(key)
    }

    fun find(key: String): StoredObject? = objects[key]
}
