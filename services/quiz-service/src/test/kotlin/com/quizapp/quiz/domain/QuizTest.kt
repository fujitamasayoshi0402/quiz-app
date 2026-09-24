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
 * 公開は出題と採点が成り立つ形（選択肢 4 つ・正解 1 つ・解説あり）を求める。
 * どちらの状態でも超えてはいけない上限（選択肢 4 つまで・正解 1 つまで）は共通。
 */
class QuizTest {

    private fun quiz(
        choices: List<Choice> = fourChoices(),
        status: QuizStatus = QuizStatus.PUBLISHED,
        question: String = "問題文",
        explanation: String = "解説",
    ) = Quiz(
        categoryId = UUID.randomUUID(),
        difficultyId = UUID.randomUUID(),
        question = question,
        explanation = explanation,
        choices = choices,
        status = status,
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
        @DisplayName("選択肢は 5 つ以上にできない")
        fun draftCannotHaveTooManyChoices() {
            val choices = fourChoices() + Choice(body = "5 つ目", isCorrect = false)
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = choices, status = QuizStatus.DRAFT) }
                .withMessage("選択肢は 4 個までです")
        }

        @Test
        @DisplayName("正解は 2 つ以上にできない")
        fun draftCannotHaveTwoCorrectChoices() {
            val choices = fourChoices().mapIndexed { i, c -> c.copy(isCorrect = i < 2) }
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = choices, status = QuizStatus.DRAFT) }
                .withMessage("正解は 1 つだけ指定してください")
        }
    }

    @Nested
    @DisplayName("公開")
    inner class Published {
        @Test
        @DisplayName("選択肢 4 つ・正解 1 つ・解説ありなら公開できる")
        fun completeQuizCanBePublished() {
            assertThatCode { quiz() }.doesNotThrowAnyException()
        }

        @Test
        @DisplayName("選択肢が 4 つ揃っていないと公開できない")
        fun requiresFourChoices() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = fourChoices().take(3)) }
                .withMessage("公開するには選択肢が 4 個必要です")
        }

        @Test
        @DisplayName("正解が無いと公開できない")
        fun requiresCorrectChoice() {
            assertThatIllegalArgumentException()
                .isThrownBy { quiz(choices = fourChoices(correct = 0)) }
                .withMessage("公開するには正解を 1 つ指定してください")
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
    }
}
