package com.quizapp.newsquiz

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * 受け箱に置く下書きの JSON。**形は `docs/news-quizzes/draft.json` の見本に固定する**（ADR-0031）。
 * 受ける側（quiz-service の `NewsQuizDraft`）とは別々にデプロイされる。項目を足すだけなら版を上げない
 */
object DraftJson {
    const val VERSION = 1

    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun write(item: NewsItem, quiz: DraftQuiz): String = mapper.writeValueAsString(
        mapOf(
            "version" to VERSION,
            "source" to mapOf(
                "url" to item.url,
                "title" to item.title,
                "publishedAt" to item.publishedAt?.toString(),
            ),
            "quiz" to mapOf(
                "question" to quiz.question,
                "choices" to quiz.choices.map { mapOf("body" to it.body, "isCorrect" to it.isCorrect) },
                "explanation" to quiz.explanation,
            ),
        ),
    )

    fun readTree(json: String) = mapper.readTree(json)
}
