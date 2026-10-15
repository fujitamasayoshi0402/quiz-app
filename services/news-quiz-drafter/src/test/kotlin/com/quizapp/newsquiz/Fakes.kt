package com.quizapp.newsquiz

import java.time.Instant

class FakeFeed(var items: List<NewsItem> = emptyList()) : Feed {
    override fun items(): List<NewsItem> = items
}

/** 選ぶのは渡された順、書くのは [quizzes] にあるもの、確かめるのは [inconsistent] にないもの */
class FakeQuizWriter(
    private val quizzes: Map<String, DraftQuiz?> = emptyMap(),
    private val inconsistent: Set<String> = emptySet(),
) : QuizWriter {
    var calls = 0

    override fun pick(items: List<NewsItem>, max: Int): List<NewsItem> {
        calls++
        return items.take(max)
    }

    override fun write(item: NewsItem): DraftQuiz? {
        calls++
        return if (item.guid in quizzes) quizzes[item.guid] else quizFor(item.guid)
    }

    override fun check(item: NewsItem, quiz: DraftQuiz): Verdict {
        calls++
        return Verdict(consistent = item.guid !in inconsistent, reason = "テスト")
    }

    override fun usage() = Usage(calls, calls * 100L, calls * 10L)

    companion object {
        fun quizFor(guid: String) = DraftQuiz(
            question = "$guid の問題",
            choices = (1..4).map { DraftChoice("$guid の選択肢 $it", isCorrect = it == 1) },
            explanation = "$guid の解説",
        )
    }
}

class InMemoryDraftStore(var waiting: Int = 0) : DraftStore {
    val files = linkedMapOf<String, String>()
    var seen: Map<String, Instant> = emptyMap()

    override fun inboxCount(atMost: Int): Int = minOf(atMost, waiting + files.size)

    override fun put(key: String, json: String) {
        files[key] = json
    }

    override fun seen(): Map<String, Instant> = seen

    override fun saveSeen(seen: Map<String, Instant>) {
        this.seen = seen
    }
}

fun item(guid: String) = NewsItem(
    guid = guid,
    title = "見出し [$guid]",
    url = "https://aws.amazon.com/about-aws/whats-new/$guid/",
    description = "$guid の説明",
    publishedAt = Instant.parse("2026-10-13T22:00:00Z"),
)
