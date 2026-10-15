package com.quizapp.newsquiz

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 1 回分の下書きを作る（ADR-0031）。
 *
 * 1. 受け箱に [Limits.maxInbox] 件たまっていれば、何もしない。確かめられていない間は、Bedrock を呼ばない（費用もかからない）
 * 2. RSS から、まだ読んでいない発表を取る
 * 3. AI に最大 [Limits.maxDrafts] 件選ばせ、1 件ずつ書かせて、決まりと、元の発表と食い違わないかを確かめる
 * 4. 確かめられたものを受け箱に置く
 * 5. 読んだ発表は、選ばなかったものも含めて記録し、二度使わない。[Limits.keepSeen] を過ぎた記録は消す
 *
 * 解説の最後の出典のリンクは、AI に書かせずにここで足す。リンク先を AI に選ばせない
 */
class Drafter(
    private val feed: Feed,
    private val writer: QuizWriter,
    private val store: DraftStore,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val limits: Limits = Limits(),
) {
    /** 1 回に作る数、受け箱にためてよい数、読んだ発表を覚えておく長さ */
    data class Limits(
        val maxDrafts: Int = DEFAULT_MAX_DRAFTS,
        val maxInbox: Int = DEFAULT_MAX_INBOX,
        val keepSeen: Duration = Duration.ofDays(DEFAULT_KEEP_SEEN_DAYS),
    )

    private val maxDrafts get() = limits.maxDrafts
    private val maxInbox get() = limits.maxInbox
    private val keepSeen get() = limits.keepSeen

    fun run() {
        val waiting = store.inboxCount(maxInbox)
        if (waiting >= maxInbox) {
            log("受け箱に確かめられていない下書きが $waiting 件あるため、作りません")
            return
        }
        val now = clock.instant()
        val seen = store.seen().filterValues { it.isAfter(now.minus(keepSeen)) }
        val fresh = feed.items().filter { it.guid !in seen }

        val written = if (fresh.isEmpty()) 0 else draft(fresh)
        store.saveSeen(seen + fresh.associate { it.guid to now })

        val usage = writer.usage()
        log(
            "下書きを作りました: 新しい発表 ${fresh.size} 件、置いたもの $written 件、" +
                "Bedrock ${usage.calls} 回（入力 ${usage.inputTokens}、出力 ${usage.outputTokens} トークン）",
        )
    }

    private fun draft(fresh: List<NewsItem>): Int {
        val room = minOf(maxDrafts, maxInbox - store.inboxCount(maxInbox))
        return writer.pick(fresh, room).count(::draftOne)
    }

    private fun draftOne(item: NewsItem): Boolean {
        val quiz = writer.write(item)?.withSource(item)
        val rejected = when {
            quiz == null -> "書けませんでした"
            quiz.problems().isNotEmpty() -> quiz.problems().joinToString("、")
            copies(item, quiz) -> "発表の文をそのまま写しています"
            else -> writer.check(item, quiz).takeUnless { it.consistent }?.let { "元の発表と食い違います: ${it.reason}" }
        }
        if (rejected != null || quiz == null) {
            log("下書きを捨てます: ${item.url}（$rejected）")
            return false
        }
        store.put(keyOf(item), DraftJson.write(item, quiz))
        return true
    }

    /**
     * 発表の文を写していないか（ADR-0031）。説明の中の [COPY_WORDS] 語の並びが、問題・選択肢・解説にそのまま出ていれば写したとみなす。
     * AI には引用しないよう指示しているが、守られるとは限らない
     */
    private fun copies(item: NewsItem, quiz: DraftQuiz): Boolean {
        val words = item.description.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val text = (listOf(quiz.question, quiz.explanation) + quiz.choices.map { it.body }).joinToString("\n")
        return words.windowed(COPY_WORDS).any { text.contains(it.joinToString(" ")) }
    }

    private fun DraftQuiz.withSource(item: NewsItem) =
        copy(explanation = "${explanation.trim()}\n\n出典: [${escape(item.title)}](${item.url})")

    /** 見出しの `[` `]` を、リンクの書式として読ませない */
    private fun escape(title: String) = title.replace("[", "\\[").replace("]", "\\]")

    /** 置いた順に並ぶよう、時刻を頭に付ける。同じ発表は同じ名前になる */
    private fun keyOf(item: NewsItem): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(item.guid.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
        return "inbox/${KEY_TIME.format(clock.instant())}-$hash.json"
    }

    companion object {
        const val DEFAULT_MAX_DRAFTS = 3
        const val DEFAULT_MAX_INBOX = 30
        const val DEFAULT_KEEP_SEEN_DAYS = 30L
        private const val HASH_LENGTH = 12

        /** これだけの語が続けて同じなら、写したとみなす。製品名（3〜4 語）は写してよい */
        private const val COPY_WORDS = 8
        private val KEY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyyMMdd'T'HHmmss",
        ).withZone(ZoneOffset.UTC)
    }
}
