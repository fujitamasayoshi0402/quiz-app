package com.quizapp.quiz.domain

import java.io.StringReader
import java.net.URI
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * 解説図の中身。draw.io の原本と、書き出した SVG の組（ADR-0017）。
 *
 * **一度置いたら変えない。** 描き直した図は、新しい ID の図として置く。
 * 配る URL のキャッシュを無効にしなくても、描き直した図が表示される。
 *
 * SVG は無害化しない。draw.io の SVG は HTML のラベルを `foreignObject` で持ち、要素を削ると表示が崩れる。
 * スクリプトは、アプリと別のオリジンと、CSP のヘッダで動かさない。ここで確かめるのは、SVG として読めることと大きさだけ。
 */
class FigureContent(val source: String, val svg: String) {
    init {
        require(source.isNotBlank()) { "図の原本（draw.io）がありません" }
        require(source.toByteArray().size <= MAX_BYTES) { "図の原本は 1 MB までです" }
        require(svg.toByteArray().size <= MAX_BYTES) { "SVG は 1 MB までです" }
        require(SvgDocuments.isSvg(svg)) { "SVG として読めません" }
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
    }
}

/**
 * 図の行。**誰に返すかは、この行が見えるかどうかで決める**（行レベルセキュリティ）。
 *
 * テナントは引数で受け取らず、要求の文脈から決める。引数にすると、別テナントの図を指せる経路ができる。
 */
interface FigureRepository {
    fun add(id: UUID)

    fun exists(id: UUID): Boolean

    /** 消したら true。見えない（無い、別テナント）なら false */
    fun delete(id: UUID): Boolean
}

/**
 * 図の本体（原本と SVG）の置き場所。テナントは [FigureRepository] と同じく、要求の文脈から決める。
 */
interface FigureStore {
    fun save(id: UUID, content: FigureContent)

    /** 原本。置かれていなければ null */
    fun findSource(id: UUID): String?

    fun delete(id: UUID)

    /** ブラウザが SVG を取りに行く URL。期限がある */
    fun svgUrl(id: UUID): URI
}

/** SVG の形だけを確かめる。DTD は読まない */
internal object SvgDocuments {
    private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

    /**
     * 外部の DTD を取りに行かず、DOCTYPE の中で宣言した実体も展開しない（XXE と展開の爆発を防ぐ）。
     * 宣言していない実体を参照すると、読めない SVG として扱われる。
     * draw.io は DOCTYPE を付けて書き出すため、DOCTYPE があること自体は拒まない
     */
    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    }

    /** 整形式の XML で、ルートが SVG の名前空間の `svg` 要素なら true */
    fun isSvg(text: String): Boolean {
        val reader = try {
            factory.createXMLStreamReader(StringReader(text))
        } catch (expected: XMLStreamException) {
            return false
        }
        return try {
            val root = generateSequence { if (reader.hasNext()) reader.next() else null }
                .firstOrNull { it == XMLStreamConstants.START_ELEMENT }
                ?.let { reader.name }
            // ルートのあとも最後まで読む。途中で壊れていれば整形式ではない
            while (reader.hasNext()) reader.next()
            root?.localPart == "svg" && root.namespaceURI == SVG_NAMESPACE
        } catch (expected: XMLStreamException) {
            false
        } finally {
            reader.close()
        }
    }
}
