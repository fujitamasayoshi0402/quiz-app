package com.quizapp.notification

import com.quizapp.events.QuizCreated
import com.quizapp.events.QuizEvent
import com.quizapp.events.QuizPublished
import com.quizapp.events.QuizRef
import com.quizapp.events.QuizUnpublished
import com.quizapp.events.QuizUpdated
import com.quizapp.events.QuizzesImported
import tools.jackson.databind.json.JsonMapper

/**
 * イベントから、Slack の Incoming Webhook に送る本文（Block Kit）を作る。
 *
 * 載せるのは、テナント、カテゴリ、問題文の冒頭、何が起きたか、管理画面へのリンク。
 * リンクはボタンにせず、文の中に置く。ボタンは押されたことを Slack がアプリへ知らせようとし、受け口のない Webhook では警告が出る
 *
 * @param webBaseUrl 画面のオリジン（例: `https://dev.example.com`）。末尾の `/` は付けない
 */
class SlackMessages(private val webBaseUrl: String) {
    private val mapper = JsonMapper.builder().build()

    fun of(event: QuizEvent): String {
        val tenant = escape(event.tenant.name)
        val message = when (event) {
            is QuizCreated -> quizMessage(event, "クイズが追加されました", event.quiz)

            is QuizUpdated -> quizMessage(event, "クイズが更新されました", event.quiz)

            is QuizPublished -> quizMessage(event, "クイズが公開されました", event.quiz)

            is QuizUnpublished -> quizMessage(event, "クイズが下書きに戻されました", event.quiz)

            is QuizzesImported -> {
                val title = "クイズを ${event.imported.total} 件取り込みました（うち公開 ${event.imported.published} 件）"
                Message(
                    fallback = "[$tenant] $title",
                    blocks = listOf(
                        section("*$title*"),
                        context("$tenant ・ ${link(adminPath(event, "quizzes"), "管理画面で開く")}"),
                    ),
                )
            }
        }
        return mapper.writeValueAsString(mapOf("text" to message.fallback, "blocks" to message.blocks))
    }

    private fun quizMessage(event: QuizEvent, title: String, quiz: QuizRef): Message {
        val tenant = escape(event.tenant.name)
        val question = escape(quiz.question)
        return Message(
            // 通知（スマホの通知やチャンネルの一覧）に出る文。blocks を表示できないところでも読める
            fallback = "[$tenant] $title: $question",
            blocks = listOf(
                section("*$title*\n$question"),
                context(
                    "$tenant ・ ${escape(quiz.category.name)} ・ " +
                        link(adminPath(event, "quizzes/${quiz.id}"), "管理画面で開く"),
                ),
            ),
        )
    }

    private fun adminPath(event: QuizEvent, path: String) = "$webBaseUrl/t/${event.tenant.slug}/admin/$path"

    private data class Message(val fallback: String, val blocks: List<Map<String, Any>>)

    private fun section(text: String) = mapOf("type" to "section", "text" to mrkdwn(text))

    private fun context(text: String) = mapOf("type" to "context", "elements" to listOf(mrkdwn(text)))

    private fun mrkdwn(text: String) = mapOf("type" to "mrkdwn", "text" to text)

    private fun link(url: String, label: String) = "<$url|$label>"

    companion object {
        /**
         * 管理者が書いた文字を、Slack の書式の記号として読ませない。
         * `<` と `>` を残すと、`<!channel>` のような全員への呼び出しやリンクを、問題文から作れてしまう
         */
        fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
