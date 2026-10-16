package com.quizapp.quiz.infrastructure.newsquiz

import com.quizapp.quiz.domain.Quiz.Companion.CHOICE_COUNT
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * Lambda が受け箱に置く下書きの形（ADR-0031）。**形は `docs/news-quizzes/draft.json` の見本に固定する。**
 * 送る側（Lambda）と受ける側（ここ）は別々にデプロイされる。項目を足すだけなら版（[version]）を上げない
 */
data class NewsQuizDraft(val version: Int, val source: Source, val quiz: Quiz) {
    data class Source(val url: String, val title: String, val publishedAt: String? = null)

    data class Quiz(val question: String, val choices: List<Choice>, val explanation: String)

    data class Choice(val body: String, val isCorrect: Boolean)

    companion object {
        const val VERSION = 1

        /** HTTP の応答に使う JsonMapper とは分ける。API のための設定の変更が、Lambda との約束に及ばないようにする */
        private val mapper: JsonMapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        /**
         * 読めない、知らない版、そのまま公開できる形でないものは例外。
         * 下書きは作りかけでも保存できるが、AI の下書きは、確かめればすぐに公開できる形で受け取る
         */
        fun parse(json: String): NewsQuizDraft {
            val draft = mapper.readValue<NewsQuizDraft>(json)
            require(draft.version == VERSION) { "知らない版の下書きです: ${draft.version}" }
            require(draft.quiz.choices.size == CHOICE_COUNT) { "選択肢が $CHOICE_COUNT つではありません" }
            require(draft.quiz.choices.count { it.isCorrect } == 1) { "正解が 1 つではありません" }
            require(draft.quiz.explanation.isNotBlank()) { "解説がありません" }
            return draft
        }
    }
}
