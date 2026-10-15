package com.quizapp.newsquiz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class DrafterTest {
    private val now = Instant.parse("2026-10-15T22:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val logs = mutableListOf<String>()

    private fun drafter(feed: Feed, writer: QuizWriter, store: DraftStore, maxDrafts: Int = 3, maxInbox: Int = 30) =
        Drafter(feed, writer, store, clock, { logs += it }, Drafter.Limits(maxDrafts = maxDrafts, maxInbox = maxInbox))

    private fun quizIn(json: String) = DraftJson.readTree(json)["quiz"]

    @Test
    @DisplayName("選んだ発表から下書きを作って受け箱に置き、解説の最後に出典のリンクを足す。読んだ発表はすべて記録する")
    fun writesDrafts() {
        val store = InMemoryDraftStore()
        val feed = FakeFeed((1..5).map { item("n$it") })

        drafter(feed, FakeQuizWriter(), store).run()

        assertThat(store.files).hasSize(3)
        assertThat(store.files.keys).allMatch { it.matches(Regex("inbox/20261015T220000-[0-9a-f]{12}\\.json")) }
        val explanation = quizIn(store.files.values.first())["explanation"].asString()
        assertThat(explanation).isEqualTo(
            "n1 の解説\n\n出典: [見出し \\[n1\\]](https://aws.amazon.com/about-aws/whats-new/n1/)",
        )
        assertThat(store.seen.keys).containsExactlyInAnyOrder("n1", "n2", "n3", "n4", "n5")
        assertThat(logs.last()).contains("置いたもの 3 件")
    }

    @Test
    @DisplayName("受け箱がたまっていれば、Bedrock を呼ばずに終わる")
    fun stopsWhenInboxIsFull() {
        val writer = FakeQuizWriter()
        val store = InMemoryDraftStore(waiting = 30)

        drafter(FakeFeed(listOf(item("n1"))), writer, store).run()

        assertThat(writer.calls).isZero()
        assertThat(store.files).isEmpty()
        assertThat(store.seen).isEmpty()
    }

    @Test
    @DisplayName("受け箱の空きより多くは作らない")
    fun respectsRoom() {
        val store = InMemoryDraftStore(waiting = 29)

        drafter(FakeFeed((1..5).map { item("n$it") }), FakeQuizWriter(), store).run()

        assertThat(store.files).hasSize(1)
    }

    @Test
    @DisplayName("読んだ発表は使わない。30 日を過ぎた記録は消す")
    fun skipsSeenAndPrunes() {
        val writer = FakeQuizWriter()
        val store = InMemoryDraftStore().apply {
            seen = mapOf("n1" to now.minus(Duration.ofDays(1)), "old" to now.minus(Duration.ofDays(31)))
        }

        drafter(FakeFeed(listOf(item("n1"))), writer, store).run()

        assertThat(writer.calls).isZero()
        assertThat(store.seen.keys).containsExactly("n1")
    }

    @Test
    @DisplayName("書けなかったもの、決まりを満たさないもの、元の発表と食い違うものは置かない")
    fun discardsBadDrafts() {
        val twoCorrect = FakeQuizWriter.quizFor("n2").let { q ->
            q.copy(choices = q.choices.map { it.copy(isCorrect = true) })
        }
        val writer = FakeQuizWriter(quizzes = mapOf("n1" to null, "n2" to twoCorrect), inconsistent = setOf("n3"))
        val store = InMemoryDraftStore()

        drafter(FakeFeed((1..4).map { item("n$it") }), writer, store, maxDrafts = 4).run()

        assertThat(store.files.values.map { quizIn(it)["question"].asString() }).containsExactly("n4 の問題")
        assertThat(logs.filter { it.startsWith("下書きを捨てます") }).hasSize(3)
    }
}
