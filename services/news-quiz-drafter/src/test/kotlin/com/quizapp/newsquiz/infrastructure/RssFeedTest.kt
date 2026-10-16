package com.quizapp.newsquiz.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class RssFeedTest {

    private fun parse(xml: String) = RssFeed.parse(xml.byteInputStream(), "https://aws.amazon.com/")

    @Test
    @DisplayName("発表の guid・見出し・リンク・説明（HTML を外す）・日時を読む。AWS の外を指すものは捨てる")
    fun readsItems() {
        val items = RssFeed.parse(javaClass.getResourceAsStream("/whats-new.xml")!!, "https://aws.amazon.com/")

        assertThat(items).hasSize(1)
        val item = items.single()
        assertThat(item.guid).isEqualTo("a1")
        assertThat(item.title).isEqualTo("Amazon ECS adds LiveTaskCount")
        assertThat(item.url).isEqualTo("https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/")
        assertThat(item.description).isEqualTo("Today, Amazon ECS announces LiveTaskCount , a new metric & more.")
        assertThat(item.publishedAt).isEqualTo(Instant.parse("2026-10-13T22:00:00Z"))
    }

    @Test
    @DisplayName("DTD を含むものは読まない（外部の実体で、Lambda のファイルを読ませない）")
    fun rejectsDoctype() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <rss><channel><item><link>https://aws.amazon.com/x</link><title>&x;</title></item></channel></rss>
        """.trimIndent()

        assertThatThrownBy { parse(xml) }.hasMessageContaining("DOCTYPE")
    }
}
