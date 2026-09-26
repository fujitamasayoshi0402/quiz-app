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

    override fun delete(key: String) {
        objects.remove(key)
    }

    fun find(key: String): StoredObject? = objects[key]
}
