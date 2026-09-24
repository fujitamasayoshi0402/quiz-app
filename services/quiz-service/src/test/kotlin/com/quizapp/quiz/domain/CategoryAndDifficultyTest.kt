package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class CategoryAndDifficultyTest {

    @Test
    @DisplayName("カテゴリ名は空白だけにできず、100 文字まで")
    fun categoryName() {
        assertThatIllegalArgumentException()
            .isThrownBy { Category(name = " ") }
            .withMessage("カテゴリ名を入力してください")
        assertThatCode { Category(name = "あ".repeat(Category.MAX_NAME_LENGTH)) }.doesNotThrowAnyException()
        assertThatIllegalArgumentException()
            .isThrownBy { Category(name = "あ".repeat(Category.MAX_NAME_LENGTH + 1)) }
            .withMessage("カテゴリ名は 100 文字以内で入力してください")
    }

    @Test
    @DisplayName("難易度名は空白だけにできず、50 文字まで")
    fun difficultyName() {
        assertThatIllegalArgumentException()
            .isThrownBy { difficulty(name = "") }
            .withMessage("難易度名を入力してください")
        assertThatCode { difficulty(name = "あ".repeat(Difficulty.MAX_NAME_LENGTH)) }.doesNotThrowAnyException()
        assertThatIllegalArgumentException()
            .isThrownBy { difficulty(name = "あ".repeat(Difficulty.MAX_NAME_LENGTH + 1)) }
            .withMessage("難易度名は 50 文字以内で入力してください")
    }

    @Test
    @DisplayName("レベルは 1 以上。上限と一意性は持たない")
    fun difficultyLevel() {
        assertThatIllegalArgumentException()
            .isThrownBy { difficulty(level = 0) }
            .withMessage("レベルは 1 以上で入力してください")
        // 同じレベルに複数の難易度が並ぶ（SAA / DVA）ため、一意性はドメインでも DB でも求めない
        val categoryId = UUID.randomUUID()
        assertThatCode {
            difficulty(categoryId = categoryId, name = "SAA", level = 2)
            difficulty(categoryId = categoryId, name = "DVA", level = 2)
            difficulty(level = Int.MAX_VALUE)
        }.doesNotThrowAnyException()
    }

    private fun difficulty(categoryId: UUID = UUID.randomUUID(), name: String = "初級", level: Int = 1) =
        Difficulty(categoryId = categoryId, name = name, level = level)
}
