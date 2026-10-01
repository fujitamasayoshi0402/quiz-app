package com.quizapp.notification

import com.quizapp.events.QuizCreated
import com.quizapp.events.QuizEvent
import com.quizapp.events.QuizPublished
import com.quizapp.events.QuizUnpublished
import com.quizapp.events.QuizUpdated
import com.quizapp.events.QuizzesImported
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.InputStream

/**
 * EventBridge が Lambda に渡すもの（`detail-type` と `detail` を持つ封筒）から、イベントを読む（ADR-0022）。
 *
 * **知らない項目は無視する。** 送る側は、版を上げずに項目を足すことがある。
 * 形は `docs/events/` の見本に固定し、`QuizEventReaderTest` が見本を読めることを確かめる
 */
object QuizEventReader {
    /** 読める版。送る側が版を上げたら、新旧どちらも読めるようにしてから送る側を替える */
    const val SUPPORTED_VERSION = 1

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    private val types: Map<String, Class<out QuizEvent>> = mapOf(
        "QuizCreated" to QuizCreated::class.java,
        "QuizUpdated" to QuizUpdated::class.java,
        "QuizPublished" to QuizPublished::class.java,
        "QuizUnpublished" to QuizUnpublished::class.java,
        "QuizzesImported" to QuizzesImported::class.java,
    )

    /** 知らない種類のイベントなら null。ルールが絞っているため届かないはずだが、届いても失敗にはしない */
    fun read(envelope: InputStream): QuizEvent? {
        val tree = mapper.readTree(envelope)
        return readDetail(tree.required("detail-type").asString(), tree.required("detail"))
    }

    fun readDetail(detailType: String, detail: JsonNode): QuizEvent? {
        val type = types[detailType] ?: return null
        // 版が違うものを黙って捨てない。例外で終わらせ、DLQ に残す。受け手を直してから流し直せる
        val version = detail.get("version")?.asInt() ?: SUPPORTED_VERSION
        if (version != SUPPORTED_VERSION) {
            throw UnsupportedEventVersionException(detailType, version)
        }
        return mapper.treeToValue(detail, type)
    }

    fun readDetail(detailType: String, detail: String): QuizEvent? = readDetail(detailType, mapper.readTree(detail))
}

class UnsupportedEventVersionException(detailType: String, version: Int) :
    RuntimeException("読めない版のイベントです: $detailType の版 $version（読めるのは ${QuizEventReader.SUPPORTED_VERSION}）")
