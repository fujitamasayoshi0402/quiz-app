package com.quizapp.quiz.infrastructure

import com.quizapp.events.QuizEvent
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * イベントを JSON にする（ADR-0022）。
 *
 * **HTTP の応答に使う JsonMapper とは分ける。** イベントの形は、別々にデプロイされる受け手との約束。
 * API のために Jackson の設定を変えたとき、イベントの形まで黙って変わらないようにする。
 * 形は `docs/events/` の見本に固定し、`QuizEventSamplesTest` が確かめる
 */
object QuizEventJson {
    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    fun write(event: QuizEvent): String = mapper.writeValueAsString(event)

    /** 見本のファイルに書き出すときに使う。読みやすいよう字下げする */
    fun writePretty(event: QuizEvent): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event)

    fun readTree(json: String) = mapper.readTree(json)
}
