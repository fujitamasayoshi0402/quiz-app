package com.quizapp.newsquiz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HandlerTest {

    @Test
    @DisplayName("check が true のときだけ、デプロイの確認として扱う")
    fun detectsDeployCheck() {
        assertThat(Handler.isCheck("""{"check":true}""")).isTrue()
        // Scheduler が毎日送る入力（modules/news-quiz-drafter）
        assertThat(Handler.isCheck("{}")).isFalse()
        assertThat(Handler.isCheck("""{"check":false}""")).isFalse()
        assertThat(Handler.isCheck("""{"check":"true"}""")).isFalse()
        assertThat(Handler.isCheck("""{"note":"check"}""")).isFalse()
        assertThat(Handler.isCheck("")).isFalse()
        assertThat(Handler.isCheck("not json")).isFalse()
    }
}
