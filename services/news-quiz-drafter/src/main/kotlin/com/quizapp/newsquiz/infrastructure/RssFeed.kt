package com.quizapp.newsquiz.infrastructure

import com.quizapp.newsquiz.Feed
import com.quizapp.newsquiz.NewsItem
import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AWS の What's New の RSS を読む（ADR-0031）。見出しと説明だけを使い、リンク先のページは読まない。
 *
 * **リンクが [sourcePrefix] の下にない発表は捨てる。** 出典にするのは、読むと決めた情報源だけ（quiz-service も確かめ直す）
 */
class RssFeed(private val url: String, private val sourcePrefix: String = "https://aws.amazon.com/") : Feed {
    private val http = HttpClient.newBuilder().connectTimeout(
        TIMEOUT,
    ).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun items(): List<NewsItem> {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        check(response.statusCode() == HTTP_OK) { "RSS を読めません (${response.statusCode()})" }
        return response.body().use { parse(it, sourcePrefix) }
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val HTTP_OK = 200
        private const val MAX_DESCRIPTION = 3000

        /**
         * RSS 2.0 の `item` を読む。**DTD と外部の実体を読まない**（XXE）。RSS に DTD は要らない
         */
        fun parse(xml: InputStream, sourcePrefix: String): List<NewsItem> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
                isXIncludeAware = false
            }
            val items = factory.newDocumentBuilder().parse(xml).getElementsByTagName("item")
            return (0 until items.length).mapNotNull { itemOf(items.item(it) as Element) }
                .filter { it.url.startsWith(sourcePrefix) }
        }

        private fun itemOf(item: Element): NewsItem? {
            val link = item.text("link") ?: return null
            return NewsItem(
                guid = item.text("guid") ?: link,
                title = item.text("title").orEmpty(),
                url = link,
                description = plain(item.text("description").orEmpty()).take(MAX_DESCRIPTION),
                publishedAt = item.text("pubDate")?.let(::instantOf),
            )
        }

        private fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        /** 説明は HTML。タグを外し、空白をまとめる */
        private fun plain(html: String): String = html
            .replace(Regex("<[^>]*>"), " ")
            .replace(
                "&nbsp;",
                " ",
            ).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun instantOf(value: String): Instant? = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (expected: DateTimeParseException) {
            null
        }
    }
}
