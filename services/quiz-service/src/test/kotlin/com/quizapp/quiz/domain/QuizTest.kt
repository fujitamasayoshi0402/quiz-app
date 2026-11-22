package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * クイズの不変条件。
 *
 * **下書きと公開で条件が違う**ことが要点。下書きは作りかけを保存する場所なので緩く、
 * 公開は出題と採点が成り立つ形（選択肢 2〜6 個・正解 1 個以上・解説あり）を求める（DEV-243）。
 * どちらの状態でも超えてはいけない上限（選択肢 6 個まで）は共通。
 */
class QuizTest {

    private fun quiz(
        choices: List<Choice> = fourChoices(),
        status: QuizStatus = QuizStatus.PUBLISHED,
        question: String = "問題文",
        explanation: String = "解説",
        sourceUrl: String? = null,
    ) = Quiz(
        categoryId = UUID.randomUUID(),
        difficultyId = UUID.randomUUID(),
        question = question,
        explanation = explanation,
        choices = choices,
        status = status,
        sourceUrl = sourceUrl,
    )

    private fun fourChoices(correct: Int = 1) = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == correct) }

    @Nested
    @DisplayName("下書き")
    inner class Draft {
        @Test
        @DisplayName("選択肢も正解も解説もなくても保存できる")
        fun incompleteDraftIsAllowed() {
            assertThatCode { quiz(choices = emptyList(), status = QuizStatus.DRAFT, explanation = "") }
                .doesNotThrowAnyException()
        }

        @Test
        @DisplayName("選択肢は 7 つ以上にできない")
        fun draftCannotHaveTooManyChoices() {
            val choices = (1..7).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = choices, status = QuizStatus.DRAFT) }
                .withMessage("選択肢は 6 個までです")
        }

        @Test
        @DisplayName("正解を複数指定できる")
        fun draftCanHaveMultipleCorrectChoices() {
            val choices = fourChoices().mapIndexed { i, c -> c.copy(isCorrect = i < 2) }
            assertThatCode { quiz(choices = choices, status = QuizStatus.DRAFT) }
                .doesNotThrowAnyException()
        }
    }

    @Nested
    @DisplayName("公開")
    inner class Published {
        @Test
        @DisplayName("選択肢 2〜6 個・正解 1 個以上・解説ありなら公開できる")
        fun completeQuizCanBePublished() {
            assertThatCode { quiz() }.doesNotThrowAnyException()
            assertThatCode { quiz(choices = fourChoices().take(2)) }.doesNotThrowAnyException()
            val sixWithTwoCorrect = (1..6).map { Choice(body = "選択肢 $it", isCorrect = it <= 2) }
            assertThatCode { quiz(choices = sixWithTwoCorrect) }.doesNotThrowAnyException()
        }

        @Test
        @DisplayName("選択肢が 2 つ未満だと公開できない")
        fun requiresAtLeastTwoChoices() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = fourChoices().take(1)) }
                .withMessage("公開するには選択肢が 2〜6 個必要です")
        }

        @Test
        @DisplayName("正解が無いと公開できない")
        fun requiresCorrectChoice() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = fourChoices(correct = 0)) }
                .withMessage("公開するには正解を 1 つ以上指定してください")
        }

        @Test
        @DisplayName("解説が空白だけだと公開できない")
        fun requiresExplanation() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(explanation = " \n") }
                .withMessage("公開するには解説を入力してください")
        }
    }

    @Nested
    @DisplayName("問題文と選択肢")
    inner class Texts {
        @Test
        @DisplayName("問題文は空白だけにできない")
        fun questionMustNotBeBlank() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(question = "　") }
                .withMessage("問題文を入力してください")
        }

        @Test
        @DisplayName("問題文は 2000 文字まで")
        fun questionLengthLimit() {
            assertThatCode { quiz(question = "あ".repeat(Quiz.MAX_QUESTION_LENGTH)) }.doesNotThrowAnyException()
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(question = "あ".repeat(Quiz.MAX_QUESTION_LENGTH + 1)) }
                .withMessage("問題文は 2000 文字以内で入力してください")
        }

        @Test
        @DisplayName("選択肢の本文は空白だけにできず、500 文字まで")
        fun choiceBody() {
            assertThatIllegalArgumentException()
                .isThrownBy { Choice(body = " ", isCorrect = false) }
                .withMessage("選択肢の本文を入力してください")
            assertThatCode { Choice(body = "あ".repeat(Choice.MAX_BODY_LENGTH), isCorrect = false) }
                .doesNotThrowAnyException()
            assertThatIllegalArgumentException()
                .isThrownBy { Choice(body = "あ".repeat(Choice.MAX_BODY_LENGTH + 1), isCorrect = false) }
                .withMessage("選択肢は 500 文字以内で入力してください")
        }

        @Test
        @DisplayName("問題文と解説が指している図の ID を両方返す")
        fun figureIdsFromQuestionAndExplanation() {
            val qFig = UUID.randomUUID()
            val eFig = UUID.randomUUID()
            val q = quiz(
                question = "次の図を見て答えよ\n![問題図](figure:$qFig)",
                explanation = "解説の図\n![解説図](figure:$eFig)",
            )
            org.assertj.core.api.Assertions.assertThat(q.figureIds()).containsExactlyInAnyOrder(qFig, eFig)
        }
    }

    @Nested
    @DisplayName("出典の URL（ADR-0031）")
    inner class SourceUrl {
        @Test
        @DisplayName("人が作ったものは持たず、AI が作ったものは https の URL を持つ")
        fun httpsOrNothing() {
            assertThatCode { quiz(sourceUrl = null) }.doesNotThrowAnyException()
            assertThatCode {
                quiz(sourceUrl = "https://aws.amazon.com/about-aws/whats-new/")
            }.doesNotThrowAnyException()
        }

        @Test
        @DisplayName("https 以外と、長すぎるものは持てない")
        fun rejectsOthers() {
            listOf("http://aws.amazon.com/", "javascript:alert(1)", "https://" + "a".repeat(Quiz.MAX_SOURCE_URL_LENGTH))
                .forEach { assertThatIllegalArgumentException().isThrownBy { quiz(sourceUrl = it) } }
        }
    }
}
