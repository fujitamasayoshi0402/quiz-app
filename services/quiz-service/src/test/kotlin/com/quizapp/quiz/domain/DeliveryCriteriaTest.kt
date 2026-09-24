package com.quizapp.quiz.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DeliveryCriteriaTest {

    @Test
    @DisplayName("出題数を指定しなければ上限まで出す。小さな既定値で黙って切らない")
    fun defaultLimitIsTheMaximum() {
        assertThat(DeliveryCriteria().limit).isEqualTo(DeliveryCriteria.MAX_QUIZ_COUNT)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 100])
    @DisplayName("出題数は 1〜100")
    fun limitWithinRange(limit: Int) {
        assertThatCode { DeliveryCriteria(limit = limit) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 101])
    @DisplayName("範囲外の出題数は受け付けない")
    fun limitOutOfRange(limit: Int) {
        assertThatIllegalArgumentException()
            .isThrownBy { DeliveryCriteria(limit = limit) }
            .withMessage("出題数は 1 以上 100 以下で指定してください")
    }

    @Test
    @DisplayName("出題対象と並びは大文字・小文字を区別せずに読む")
    fun parsesCaseInsensitively() {
        assertThat(DeliveryScope.from("unanswered_only")).isEqualTo(DeliveryScope.UNANSWERED_ONLY)
        assertThat(DeliveryScope.from("UNANSWERED")).isEqualTo(DeliveryScope.UNANSWERED)
        assertThat(DeliveryOrder.from("Registered")).isEqualTo(DeliveryOrder.REGISTERED)
    }

    @Test
    @DisplayName("知らない値は、選べる値を示して拒否する")
    fun rejectsUnknownValues() {
        assertThatIllegalArgumentException()
            .isThrownBy { DeliveryScope.from("unanswered-only") }
            .withMessageContaining("all / unanswered / unanswered_only")
        assertThatIllegalArgumentException()
            .isThrownBy { DeliveryOrder.from("shuffle") }
            .withMessageContaining("random / registered")
    }
}
