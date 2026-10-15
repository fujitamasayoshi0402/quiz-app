package com.quizapp.newsquiz

import java.time.Instant

/** 公式の発表を読む（AWS の What's New の RSS） */
fun interface Feed {
    fun items(): List<NewsItem>
}

/** AI に、選ばせる・書かせる・確かめさせる（Bedrock） */
interface QuizWriter {
    /** クイズにできる（事実がはっきりした）ものを、最大 [max] 件選ぶ */
    fun pick(items: List<NewsItem>, max: Int): List<NewsItem>

    /** 書けなければ null */
    fun write(item: NewsItem): DraftQuiz?

    fun check(item: NewsItem, quiz: DraftQuiz): Verdict

    /** ここまでに使ったトークン */
    fun usage(): Usage
}

/** 受け箱（`inbox/`）と、読んだ発表の記録（`state/`）。S3 */
interface DraftStore {
    /** 受け箱の件数。[atMost] まで数えれば足りる */
    fun inboxCount(atMost: Int): Int

    fun put(key: String, json: String)

    /** 読んだ発表の guid と、読んだ時刻 */
    fun seen(): Map<String, Instant>

    fun saveSeen(seen: Map<String, Instant>)
}
