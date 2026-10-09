package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.auth.CurrentUser
import com.quizapp.auth.UserContext
import com.quizapp.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID

/**
 * 何を「利用者の要求で DB を使った」と数えるか。拾い直しが動き出すきっかけになる。
 * 利用者を特定できた要求だけを数える（特定するときに、DB から利用者を引いている）
 */
class DatabaseActivityTest {

    private val clock = MutableClock()
    private val activity = DatabaseActivity(clock)
    private val filter = DatabaseActivityFilter(activity)

    @AfterEach
    fun tearDown() = UserContext.clear()

    private fun request() =
        filter.doFilter(MockHttpServletRequest("GET", "/api/me/tenants"), MockHttpServletResponse(), MockFilterChain())

    @Test
    @DisplayName("利用者を特定できた要求は、DB を使ったものとして数える")
    fun authenticatedRequestCounts() {
        UserContext.set(CurrentUser(UUID.randomUUID()))

        request()

        assertThat(activity.usedWithin(Duration.ofMinutes(5))).isTrue()
    }

    @Test
    @DisplayName("アクセストークンを持たない要求は数えない。DB に触れずに 401 になる")
    fun anonymousRequestDoesNotCount() {
        request()

        assertThat(activity.usedWithin(Duration.ofMinutes(5))).isFalse()
    }

    @Test
    @DisplayName("使ってから時間がたつと、使っていないことになる")
    fun expires() {
        activity.record()
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))

        assertThat(activity.usedWithin(Duration.ofMinutes(5))).isFalse()
    }
}
