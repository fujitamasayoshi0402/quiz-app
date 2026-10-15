package com.quizapp.newsquiz.infrastructure

import com.quizapp.newsquiz.DraftStore
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Instant

/**
 * 受け箱（`inbox/`）と、読んだ発表の記録（`state/seen.json`）を S3 に置く（ADR-0031）。
 * quiz-service は `inbox/` を読んで消す。この Lambda は `inbox/` に置いて数え、`state/` を読み書きする
 */
class S3DraftStore(private val client: S3Client, private val bucket: String) : DraftStore {
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    override fun inboxCount(atMost: Int): Int =
        client.listObjectsV2 { it.bucket(bucket).prefix(INBOX).maxKeys(atMost) }.keyCount()

    override fun put(key: String, json: String) {
        client.putObject({ it.bucket(bucket).key(key).contentType(JSON) }, RequestBody.fromString(json))
    }

    override fun seen(): Map<String, Instant> = try {
        val json = client.getObjectAsBytes { it.bucket(bucket).key(SEEN) }.asUtf8String()
        mapper.readValue<Map<String, String>>(json).mapValues { Instant.parse(it.value) }
    } catch (expected: NoSuchKeyException) {
        emptyMap()
    }

    override fun saveSeen(seen: Map<String, Instant>) {
        put(SEEN, mapper.writeValueAsString(seen.mapValues { it.value.toString() }))
    }

    private companion object {
        const val INBOX = "inbox/"
        const val SEEN = "state/seen.json"
        const val JSON = "application/json"
    }
}
