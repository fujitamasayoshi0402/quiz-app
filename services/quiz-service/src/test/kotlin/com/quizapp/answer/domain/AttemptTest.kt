package com.quizapp.answer.domain

import com.quizapp.quiz.domain.DeliveredChoice
import com.quizapp.quiz.domain.DeliveredQuiz
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 選択肢の並び。
 *
 * **挑戦ごとに入れ替わり、同じ挑戦の中では変わらない**ことが要点。
 * 入れ替わらなければ正解の位置で当てられ、挑戦の中で変われば再開や結果の画面で並びがずれる。
 */
class AttemptTest {

    private val quiz = DeliveredQuiz(
        id = UUID.randomUUID(),
        categoryId = UUID.randomUUID(),
        difficultyId = UUID.randomUUID(),
        question = "問題",
        choices = (1..4).map { DeliveredChoice(UUID.randomUUID(), "選択肢 $it") },
    )

    private fun attempt() = Attempt(id = UUID.randomUUID(), userId = UUID.randomUUID())

    @Test
    @DisplayName("同じ挑戦なら、何度並べても同じ並びになる")
    fun sameAttemptKeepsOrder() {
        val attempt = attempt()

        assertThat(attempt.arrange(quiz)).isEqualTo(attempt.arrange(quiz))
    }

    @Test
    @DisplayName("並べ替えるだけで、選択肢は増えも減りもしない")
    fun keepsChoices() {
        assertThat(attempt().arrange(quiz).choices).containsExactlyInAnyOrderElementsOf(quiz.choices)
    }

    @Test
    @DisplayName("挑戦が変われば、先頭の選択肢はどの位置にも来る")
    fun differentAttemptsMoveChoices() {
        val first = quiz.choices.first()

        // 100 回で一度も来ない位置が残る確率は 4 × (3/4)^100 ≒ 10^-12
        val positions = (1..100).map { attempt().arrange(quiz).choices.indexOf(first) }.toSet()

        assertThat(positions).containsExactlyInAnyOrder(0, 1, 2, 3)
    }
}
