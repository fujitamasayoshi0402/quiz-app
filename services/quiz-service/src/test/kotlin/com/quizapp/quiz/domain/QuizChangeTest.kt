package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 保存の前後から、送るイベントを 1 つに決める（ADR-0022）。
 *
 * 要点は 2 つ。状態が変わった保存は、中身が変わっていても公開・取り下げだけにすること。
 * 何も変えずに保存したときは、何も送らないこと。
 */
class QuizChangeTest {

    private val before = Quiz(
        id = UUID.randomUUID(),
        categoryId = UUID.randomUUID(),
        difficultyId = UUID.randomUUID(),
        question = "問題文",
        explanation = "解説",
        choices = (1..4).map { Choice(id = UUID.randomUUID(), body = "選択肢 $it", isCorrect = it == 1) },
        status = QuizStatus.DRAFT,
    )

    /** 画面から届く形。選択肢の ID は持たない（保存のたびに振り直される） */
    private fun Quiz.resubmitted() = copy(choices = choices.map { it.copy(id = null) })

    @Test
    @DisplayName("下書きを公開すると、中身を変えていても公開だけになる")
    fun publishWinsOverUpdate() {
        val after = before.resubmitted().copy(question = "書き換えた問題文", status = QuizStatus.PUBLISHED)

        assertThat(QuizChange.between(before, after)).isEqualTo(QuizChange.PUBLISHED)
    }

    @Test
    @DisplayName("公開を下書きに戻すと、取り下げになる")
    fun unpublish() {
        val published = before.copy(status = QuizStatus.PUBLISHED)

        assertThat(QuizChange.between(published, published.resubmitted().copy(status = QuizStatus.DRAFT)))
            .isEqualTo(QuizChange.UNPUBLISHED)
    }

    @Test
    @DisplayName("状態を変えずに中身を変えると、更新になる")
    fun update() {
        assertThat(QuizChange.between(before, before.resubmitted().copy(explanation = "書き換えた解説")))
            .isEqualTo(QuizChange.UPDATED)
        assertThat(QuizChange.between(before, before.resubmitted().copy(difficultyId = UUID.randomUUID())))
            .isEqualTo(QuizChange.UPDATED)
    }

    @Test
    @DisplayName("選択肢の正解や並びを変えると、更新になる")
    fun choiceChangesAreUpdates() {
        val choices = before.choices.map { it.copy(id = null) }
        val correctMoved = choices.mapIndexed { i, choice -> choice.copy(isCorrect = i == 1) }

        assertThat(QuizChange.between(before, before.copy(choices = correctMoved))).isEqualTo(QuizChange.UPDATED)
        assertThat(QuizChange.between(before, before.copy(choices = choices.reversed())))
            .isEqualTo(QuizChange.UPDATED)
    }

    @Test
    @DisplayName("何も変えずに保存したときは、何も送らない。選択肢の ID が振り直されても同じ")
    fun unchangedSaveSendsNothing() {
        assertThat(QuizChange.between(before, before.resubmitted())).isNull()
    }
}
