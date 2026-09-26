package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FigureContentTest {

    private val source = """<mxfile><diagram name="1">x</diagram></mxfile>"""

    @Test
    @DisplayName("draw.io が書き出す形（XML 宣言と DOCTYPE つき）の SVG を受け付ける")
    fun acceptsDrawioSvg() {
        val svg = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="10" height="10">
              <g><foreignObject width="10" height="10"><div xmlns="http://www.w3.org/1999/xhtml">ラベル &amp; 図</div></foreignObject></g>
            </svg>
        """.trimIndent()

        assertThat(FigureContent(source, svg).svg).isEqualTo(svg)
    }

    @Test
    @DisplayName("ルートが SVG の名前空間の svg でなければ受け付けない")
    fun rejectsNonSvgRoot() {
        listOf(
            """<html xmlns="http://www.w3.org/1999/xhtml"><svg xmlns="http://www.w3.org/2000/svg"/></html>""",
            """<svg width="10"/>""",
            """<x:svg xmlns:x="urn:example"/>""",
        ).forEach { svg ->
            assertThatThrownBy { FigureContent(source, svg) }.hasMessage("SVG として読めません")
        }
    }

    @Test
    @DisplayName("整形式でない XML は受け付けない")
    fun rejectsMalformedXml() {
        listOf("", "not xml", """<svg xmlns="http://www.w3.org/2000/svg"><g></svg>""").forEach { svg ->
            assertThatThrownBy { FigureContent(source, svg) }.hasMessage("SVG として読めません")
        }
    }

    @Test
    @DisplayName("DOCTYPE で宣言した実体は展開しない。外部の実体も内部の実体も、参照すれば読めない SVG になる")
    fun doesNotExpandEntities() {
        val external = """
            <!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <svg xmlns="http://www.w3.org/2000/svg"><text>&xxe;</text></svg>
        """.trimIndent()
        val laughs = """
            <!DOCTYPE svg [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
            <svg xmlns="http://www.w3.org/2000/svg"><text>&b;</text></svg>
        """.trimIndent()

        listOf(external, laughs).forEach { svg ->
            assertThatThrownBy { FigureContent(source, svg) }.hasMessage("SVG として読めません")
        }
    }

    @Test
    @DisplayName("SVG と原本は、それぞれ 1 MB まで")
    fun limitsSize() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"/>"""
        // 日本語は UTF-8 で 3 バイト。文字数ではなくバイト数で数える
        val tooLarge = "あ".repeat(FigureContent.MAX_BYTES / 3 + 1)

        assertThatThrownBy { FigureContent(tooLarge, svg) }.hasMessage("図の原本は 1 MB までです")
        assertThatThrownBy { FigureContent(source, "<svg xmlns=\"http://www.w3.org/2000/svg\"><!--$tooLarge--></svg>") }
            .hasMessage("SVG は 1 MB までです")
        assertThatThrownBy { FigureContent(" ", svg) }.hasMessage("図の原本（draw.io）がありません")
    }
}
