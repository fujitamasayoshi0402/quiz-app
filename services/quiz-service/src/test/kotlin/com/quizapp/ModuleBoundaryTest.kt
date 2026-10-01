package com.quizapp

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.quiz.domain.AnsweredQuizzes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties

/**
 * quiz と answer のモジュールの境界が保たれているか（ADR-0004、ADR-0023）。
 *
 * answer は quiz-service の中のモジュールのまま分けないと決めた（ADR-0023）。境界は規律で守ることになるため、
 * **崩れたらここで落とす。** 分けたくなったときに、実装を HTTP の呼び出しに替えるだけで済む状態を保つ。
 *
 * - **相手のスキーマの表に触れない。** answer のソースは `quiz` の表を、quiz のソースは `answer` の表を書かない。
 *   表の名前はマイグレーションの `CREATE TABLE` から拾うため、表を足しても、このテストを直さずに済む。
 *   見るのは文字列の中（SQL と `@Table` の引数）だけ。コードの `quiz.choices`（変数のプロパティ）と取り違えない
 * - **相手の型は、呼び合う 2 つのインターフェース（[QuizCatalog]、[AnsweredQuizzes]）に出るものだけを使う。**
 *   インターフェースの引数と戻り値から、プロパティをたどって届く型までを許す
 *
 * ソースを読んで確かめる。クラスファイルを解析するライブラリ（ArchUnit）は足さない。
 * モジュールをまたぐ参照には import か完全な名前が要るため、ソースの文字列で足りる。
 * コメントの中の名前も違反として数える。境界をまたいだ説明は、型ではなく ADR を指す
 */
class ModuleBoundaryTest {

    private data class Module(val name: String, val packageName: String, val schema: String) {
        val sources: Path = SOURCE_ROOT.resolve(name)
    }

    companion object {
        /** テストの作業ディレクトリはモジュール直下（services/quiz-service） */
        private val SOURCE_ROOT: Path = Path.of("src/main/kotlin/com/quizapp")
        private val MIGRATIONS: Path = Path.of("src/main/resources/db/migration")

        private val QUIZ = Module("quiz", "com.quizapp.quiz", "quiz")
        private val ANSWER = Module("answer", "com.quizapp.answer", "answer")

        /** 呼び合う 2 つのインターフェース。境界をまたいでよいのは、ここに出る型だけ */
        private val CONTRACTS: List<KClass<*>> = listOf(QuizCatalog::class, AnsweredQuizzes::class)

        /** `CREATE TABLE quiz.quizzes` から、スキーマごとの表の名前を拾う */
        fun tablesBySchema(migrations: List<String>): Map<String, Set<String>> = migrations
            .flatMap { Regex("""CREATE TABLE\s+(\w+)\.(\w+)""", RegexOption.IGNORE_CASE).findAll(it) }
            .groupBy({ it.groupValues[1].lowercase() }, { it.groupValues[2].lowercase() })
            .mapValues { it.value.toSet() }

        /**
         * ソースの中で、[schema] の表を指しているところ（SQL の `quiz.quizzes`、`@Table(schema = "quiz")`）。
         * 表の名前は文字列の中だけを見る
         */
        fun tableReferences(source: String, schema: String, tables: Set<String>): List<String> {
            val qualified = Regex("""\b$schema\.(${tables.joinToString("|")})\b""")
            val annotated = Regex("""schema\s*=\s*"$schema"""")
            return stringLiterals(source).flatMap { literal -> qualified.findAll(literal).map { it.value } } +
                annotated.findAll(source).map { it.value }
        }

        /** 文字列の中身。三重の引用符で囲んだものと、ふつうの引用符で囲んだもの */
        private fun stringLiterals(source: String): List<String> {
            val raw = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
            val rawStrings = raw.findAll(source).map { it.groupValues[1] }.toList()
            val plain = Regex(""""((?:\\.|[^"\\\n])*)"""")
            return rawStrings + plain.findAll(raw.replace(source, "")).map { it.groupValues[1] }.toList()
        }

        /**
         * ソースの中で、[packageName] の下の型を指しているところ。完全な名前（`com.quizapp.quiz.domain.Quiz`）で返す。
         * `import ... .*` は、何を使っているか分からないため、そのまま違反として返す
         */
        fun typeReferences(source: String, packageName: String): List<String> =
            Regex("""\b${Regex.escape(packageName)}\.(?:[a-z]\w*\.)*(?:[A-Z]\w*|\*)""")
                .findAll(source)
                .map { it.value }
                .toList()

        /** 2 つのインターフェースから、引数・戻り値・プロパティをたどって届く、このアプリの型 */
        fun contractTypes(roots: List<KClass<*>>): Set<String> {
            val found = mutableSetOf<KClass<*>>()

            fun visit(type: KType) {
                type.arguments.mapNotNull { it.type }.forEach(::visit)
                val classifier = (type.classifier as? KClass<*>)
                    ?.takeIf { it.qualifiedName?.startsWith("com.quizapp.") == true }
                if (classifier != null && found.add(classifier)) {
                    classifier.memberProperties
                        .filter { it.visibility == KVisibility.PUBLIC }
                        .forEach { visit(it.returnType) }
                }
            }

            roots.forEach { root ->
                found += root
                root.declaredMemberFunctions.forEach { function ->
                    function.parameters.drop(1).forEach { visit(it.type) }
                    visit(function.returnType)
                }
            }
            return found.mapNotNull { it.qualifiedName }.toSet()
        }

        private fun sourcesOf(module: Module): Map<Path, String> = Files.walk(module.sources).use { paths ->
            paths.filter { it.extension == "kt" }.toList().associateWith { it.readText() }
        }
    }

    private val tables = tablesBySchema(
        Files.list(MIGRATIONS).use { paths -> paths.filter { it.extension == "sql" }.toList().map { it.readText() } },
    )
    private val allowedTypes = contractTypes(CONTRACTS)

    /** [from] のソースのうち、[to] の表に触れているところ。`ファイル: 参照` の形 */
    private fun tableViolations(from: Module, to: Module): List<String> = sourcesOf(from).flatMap { (path, source) ->
        tableReferences(source, to.schema, tables.getValue(to.schema)).map { "${SOURCE_ROOT.relativize(path)}: $it" }
    }

    /** [from] のソースのうち、[to] の型をインターフェースの外で使っているところ */
    private fun typeViolations(from: Module, to: Module): List<String> = sourcesOf(from).flatMap { (path, source) ->
        typeReferences(source, to.packageName)
            .filterNot { it in allowedTypes }
            .map { "${SOURCE_ROOT.relativize(path)}: $it" }
    }

    @Nested
    @DisplayName("スキーマ")
    inner class Schemas {
        @Test
        @DisplayName("answer のソースは、quiz のスキーマの表に触れない")
        fun answerDoesNotTouchQuizTables() {
            assertThat(tableViolations(ANSWER, QUIZ))
                .describedAs("quiz の表は QuizCatalog を通して読む（ADR-0023）")
                .isEmpty()
        }

        @Test
        @DisplayName("quiz のソースは、answer のスキーマの表に触れない")
        fun quizDoesNotTouchAnswerTables() {
            assertThat(tableViolations(QUIZ, ANSWER))
                .describedAs("answer の表は AnsweredQuizzes を通して読む（ADR-0023）")
                .isEmpty()
        }

        @Test
        @DisplayName("マイグレーションから、両方のスキーマの表を拾えている")
        fun tablesAreFound() {
            // 拾えていなければ、上の 2 つは何も確かめずに通ってしまう
            assertThat(tables[QUIZ.schema]).contains("quizzes", "choices")
            assertThat(tables[ANSWER.schema]).contains("attempts", "answers")
        }
    }

    @Nested
    @DisplayName("型")
    inner class Types {
        @Test
        @DisplayName("answer が使う quiz の型は、2 つのインターフェースに出るものだけ")
        fun answerUsesOnlyContractTypesOfQuiz() {
            assertThat(typeViolations(ANSWER, QUIZ))
                .describedAs("インターフェースに出ない quiz の型を使っている。QuizCatalog に足すか、answer の中に型を持つ（ADR-0023）")
                .isEmpty()
        }

        @Test
        @DisplayName("quiz が使う answer の型は、2 つのインターフェースに出るものだけ")
        fun quizUsesOnlyContractTypesOfAnswer() {
            assertThat(typeViolations(QUIZ, ANSWER))
                .describedAs("インターフェースに出ない answer の型を使っている。AnsweredQuizzes に足すか、quiz の中に型を持つ（ADR-0023）")
                .isEmpty()
        }

        @Test
        @DisplayName("使ってよい型には、インターフェースそのものと、引数と戻り値からたどれる型が入る")
        fun contractTypesFollowSignatures() {
            // 中身が空だと、上の 2 つは何でも違反にしてしまう。広すぎると、何も確かめない
            assertThat(allowedTypes).contains(
                "com.quizapp.answer.domain.QuizCatalog",
                "com.quizapp.quiz.domain.AnsweredQuizzes",
                "com.quizapp.quiz.domain.DeliveredQuiz",
                // DeliveredQuiz のプロパティからたどる
                "com.quizapp.quiz.domain.DeliveredChoice",
                "com.quizapp.quiz.domain.AnswerKey",
            )
            assertThat(allowedTypes).doesNotContain("com.quizapp.quiz.domain.Quiz")
        }
    }

    @Nested
    @DisplayName("見つけ方")
    inner class Detection {
        @Test
        @DisplayName("SQL と @Table の、相手の表への参照を見つける。パッケージの名前や変数のプロパティは表と取り違えない")
        fun findsTableReferences() {
            val source = """
                import com.quizapp.quiz.domain.DeliveredQuiz
                val sql = "SELECT q.id FROM quiz.quizzes q JOIN quiz.choices c ON c.quiz_id = q.id"
                @Table(schema = "quiz", name = "quizzes")
                val shuffled = quiz.choices.shuffled()
            """.trimIndent()

            assertThat(tableReferences(source, "quiz", setOf("quizzes", "choices")))
                .containsExactlyInAnyOrder("quiz.quizzes", "quiz.choices", "schema = \"quiz\"")
        }

        @Test
        @DisplayName("import と完全な名前の型を見つける。* の import も違反として返す")
        fun findsTypeReferences() {
            val source = """
                import com.quizapp.quiz.domain.Quiz
                import com.quizapp.quiz.infrastructure.*
                @Schema(description = com.quizapp.quiz.domain.Quiz.EXPLANATION_FORMAT)
                import com.quizapp.answer.domain.QuizCatalog
            """.trimIndent()

            assertThat(typeReferences(source, "com.quizapp.quiz")).containsExactly(
                "com.quizapp.quiz.domain.Quiz",
                "com.quizapp.quiz.infrastructure.*",
                "com.quizapp.quiz.domain.Quiz",
            )
        }
    }
}
