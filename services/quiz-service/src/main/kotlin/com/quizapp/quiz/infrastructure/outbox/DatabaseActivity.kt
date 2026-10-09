package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.auth.AuthenticationFilter
import com.quizapp.auth.UserContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 利用者の要求で、最後に DB を使った時刻（ADR-0022）。拾い直し（[OutboxRelay]）は、この時刻から少しの間だけ動く。
 *
 * **拾い直しが DB を使っても、ここには数えない。** 数えると、拾い直しが自分で DB を使い続け、Aurora が一時停止しなくなる。
 * タスクごとに持つ。起動した直後は空で、利用者が使うまで拾い直しは動かない（夜間の停止の明けに、毎朝 Aurora を起こさない）
 */
class DatabaseActivity(private val clock: Clock) {
    @Volatile private var lastUsedAt: Instant? = null

    fun record() {
        lastUsedAt = clock.instant()
    }

    fun usedWithin(window: Duration): Boolean = lastUsedAt?.let { !it.isBefore(clock.instant().minus(window)) } ?: false
}

/**
 * 利用者を特定できた要求を、DB を使ったものとして記録する。
 *
 * 利用者の特定（[AuthenticationFilter]）は、毎回 DB から利用者を引く。特定できたなら、DB は起きている。
 * アクセストークンを持たない要求は記録しない。DB に触れずに 401 になる要求で、拾い直しを動かさない
 */
class DatabaseActivityFilter(private val activity: DatabaseActivity) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (UserContext.get() != null) activity.record()
        filterChain.doFilter(request, response)
    }

    companion object {
        /** 利用者の特定より後 */
        const val ORDER = AuthenticationFilter.ORDER + 10
    }
}
