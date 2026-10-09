package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 解説の本文から、指している図を拾う（ADR-0020）。
 *
 * 拾い漏らすと、テナントにない図を指した解説が保存できてしまう。
 * 画面が図として出す書き方（`figure:` のあとに UUID）は、すべて拾う。
 */
class FigureReferencesTest {

    private val first = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val second = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")

    @Test
    @DisplayName("画像の記法とリンクの記法の両方から拾い、同じ図は 1 つにまとめる")
    fun findsImagesAndLinks() {
        val text = """
            ![構成図](figure:$first)
            [資料](figure:$second) と、もう一度 ![構成図](figure:$first)
        """.trimIndent()

        assertThat(FigureReferences.findIn(text)).containsExactlyInAnyOrder(first, second)
    }

    @Test
    @DisplayName("大文字の UUID も拾う。画面も大文字を図として出すため")
    fun ignoresCase() {
        assertThat(FigureReferences.findIn("![図](FIGURE:${first.toString().uppercase()})")).containsExactly(first)
    }

    @Test
    @DisplayName("UUID の形でないものは拾わない。画面も図として出さない")
    fun ignoresMalformedIds() {
        val text = "![図](figure:not-a-uuid) ![図](figure:${first}0) ![図](figure:${first.toString().drop(1)})"

        assertThat(FigureReferences.findIn(text)).isEmpty()
    }

    @Test
    @DisplayName("図を指していない解説からは何も拾わない")
    fun findsNothingInPlainText() {
        assertThat(FigureReferences.findIn("figure: という言葉だけの解説")).isEmpty()
    }
}
