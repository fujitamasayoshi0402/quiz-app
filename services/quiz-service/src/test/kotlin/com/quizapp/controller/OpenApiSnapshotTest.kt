package com.quizapp.controller

import com.quizapp.quiz.support.TestPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * OpenAPI 定義とコードの乖離を検出する。
 *
 * 定義はコードから生成する（ADR-0010）。
 * 生成物をリポジトリに固定して持ち、**このテストが差分を落とす**。
 *
 * 固定しない運用だと、API の変更が差分に現れない。
 * レビューで気づけず、フロントの型が黙って変わる。
 *
 * 更新するとき:
 * ```
 * UPDATE_OPENAPI=true ./gradlew :services:quiz-service:test --tests '*OpenApiSnapshotTest'
 * ```
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSnapshotTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        /** テストの作業ディレクトリはモジュール直下（services/quiz-service） */
        private val SNAPSHOT: Path = Path.of("../../docs/api/openapi.yaml")
        private val GENERATED: Path = Path.of("build/openapi-generated.yaml")
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("生成した定義が docs/api/openapi.yaml と一致する")
    fun snapshotMatchesGeneratedDefinition() {
        // contentAsString は応答の charset に従う。springdoc の YAML は charset を宣言しないため、
        // そのまま読むと ISO-8859-1 として解釈されて日本語が壊れる
        val generated = mockMvc.get("/v3/api-docs.yaml")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)

        if (System.getenv("UPDATE_OPENAPI") == "true") {
            Files.createDirectories(SNAPSHOT.toAbsolutePath().parent)
            Files.writeString(SNAPSHOT, generated)
            return
        }

        if (Files.notExists(SNAPSHOT)) {
            writeGenerated(generated)
            throw AssertionError("OpenAPI 定義がありません。UPDATE_OPENAPI=true で生成してください: $SNAPSHOT")
        }

        if (generated != Files.readString(SNAPSHOT)) {
            writeGenerated(generated)
            throw AssertionError(
                """
                OpenAPI 定義がコードと一致しません。
                生成結果: ${GENERATED.toAbsolutePath()}
                更新: UPDATE_OPENAPI=true ./gradlew :services:quiz-service:test --tests '*OpenApiSnapshotTest'
                """.trimIndent(),
            )
        }
    }

    @Test
    @DisplayName("出題のスキーマに正解と解説が現れない")
    fun deliverySchemaHasNoAnswer() {
        val spec = mockMvc.get("/v3/api-docs").andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)

        // 型で正解を持たせていないことが、定義にも現れているかを確かめる。
        // ここが崩れると、生成したクライアントの型から正解が読めてしまう
        assertThat(spec).contains("DeliveredQuiz", "DeliveredChoice")
        assertThat(extractSchema(spec, "DeliveredChoice")).doesNotContain("isCorrect")
        assertThat(extractSchema(spec, "DeliveredQuiz")).doesNotContain("isCorrect", "explanation")
    }

    @Test
    @DisplayName("定義に壊れた参照がない")
    fun everyReferenceResolves() {
        val spec = mockMvc.get("/v3/api-docs").andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)

        val referenced = Regex("#/components/schemas/([A-Za-z0-9_]+)")
            .findAll(spec).map { it.groupValues[1] }.toSet()
        val defined = Regex("\"([A-Za-z0-9_]+)\":\\{")
            .findAll(spec.substringAfter("\"schemas\":{")).map { it.groupValues[1] }.toSet()

        // 参照だけあって定義がないと、生成したクライアントの型が壊れる。
        // OpenAPI の Bean に載せたスキーマは springdoc に置き換えられて消えるため、実際に踏んだ
        assertThat(referenced - defined).describedAs("参照されているが定義されていないスキーマ").isEmpty()
    }

    @Test
    @DisplayName("is 始まりのプロパティが、実際の JSON と同じ名前で定義される")
    fun booleanPropertyNamesMatchJson() {
        val spec = mockMvc.get("/v3/api-docs").andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)

        // Kotlin の `isCorrect` は Java の getter 規約では `correct` と読まれる。
        // Jackson は Kotlin のプロパティ名で出すため、放っておくと定義と実際の JSON がずれ、
        // 生成したクライアントが送るキーをサーバーが受け取れない
        listOf("ChoiceRequest", "ChoiceResponse", "AnswerResult", "QuizResult").forEach { name ->
            val schema = extractSchema(spec, name)
            assertThat(schema).describedAs(name).contains(""""isCorrect"""")
            assertThat(schema).describedAs(name).doesNotContain(""""correct":""")
        }
    }

    private fun writeGenerated(content: String) {
        Files.createDirectories(GENERATED.toAbsolutePath().parent)
        Files.writeString(GENERATED, content)
    }

    /** `"Name":{...}` の対応する閉じ括弧までを切り出す。 */
    private fun extractSchema(spec: String, name: String): String {
        val start = spec.indexOf("\"$name\":{")
        assertThat(start).describedAs("スキーマ %s が定義にない", name).isNotNegative()

        var depth = 0
        var i = spec.indexOf('{', start)
        val from = i
        while (i < spec.length) {
            when (spec[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return spec.substring(from, i + 1)
            }
            i++
        }
        return spec.substring(from)
    }
}
