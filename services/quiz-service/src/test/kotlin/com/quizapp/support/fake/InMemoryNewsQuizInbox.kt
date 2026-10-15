package com.quizapp.support.fake

import com.quizapp.quiz.infrastructure.newsquiz.NewsQuizInbox
import java.util.TreeMap

/** 技術ニュースの下書きの受け箱。キーの順に返す（S3 の一覧と同じ） */
class InMemoryNewsQuizInbox : NewsQuizInbox {
    private val files = TreeMap<String, String>()

    fun put(key: String, json: String) {
        files[key] = json
    }

    fun keys(): Set<String> = files.keys.toSet()

    override fun keys(limit: Int): List<String> = files.keys.take(limit)

    override fun read(key: String): String? = files[key]

    override fun delete(key: String) {
        files.remove(key)
    }
}
