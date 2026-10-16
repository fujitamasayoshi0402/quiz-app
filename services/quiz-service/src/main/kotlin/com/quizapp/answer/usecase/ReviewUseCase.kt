package com.quizapp.answer.usecase

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.answer.domain.ReviewSchedule
import com.quizapp.answer.domain.ReviewScheduleRepository
import com.quizapp.answer.domain.StudyDay
import com.quizapp.auth.UserContext
import com.quizapp.tenant.TenantTransaction
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * 間隔反復の「今日の復習」（DEV-178、ADR-0033）。**扱えるのは自分の記録だけ。**
 *
 * 次に出す日は、回答を受けた挑戦のトランザクションの中で [record] が更新する。
 * 出題は出題対象 `review` が受け持ち、ここは件数と次の日を返す。
 */
@Service
class ReviewUseCase(
    private val schedules: ReviewScheduleRepository,
    private val catalog: QuizCatalog,
    private val clock: Clock,
    private val tenantTransaction: TenantTransaction,
) {
    /** 回答を 1 つ受けて、その問題を次に出す日を決め直す。トランザクションは呼び出し側（挑戦の回答）が張る */
    fun record(userId: UUID, quizId: UUID, correct: Boolean) {
        val today = StudyDay.today(clock)
        val next = schedules.find(userId, quizId)?.after(correct, today)
            ?: ReviewSchedule.first(quizId, correct, today)
        schedules.save(userId, next)
    }

    /**
     * 今日の復習の件数と、0 件のときに次に出る日。**いま出題できるクイズだけを数える。**
     * 削除・非公開にしたクイズの記録は残るが、出題されないので数えない（「あとで見直す」印と同じ）。
     */
    fun summary(): ReviewSummary = tenantTransaction.execute {
        val today = StudyDay.today(clock)
        val playable = catalog.findPlayableQuizIds()
        val (due, later) = schedules.findAll(UserContext.require())
            .filter { it.quizId in playable }
            .partition { it.isDue(today) }

        ReviewSummary(dueCount = due.size, nextDueOn = later.minOfOrNull { it.dueOn }, today = today)
    }
}

data class ReviewSummary(
    @field:Schema(description = "今日の復習の問題の数。次に出す日が今日（日本時間）までのもの")
    val dueCount: Int,
    @field:Schema(description = "今日より後で、いちばん早く復習が来る日。復習を待つ問題がなければ null")
    val nextDueOn: LocalDate?,
    @field:Schema(description = "サーバーが今日とした日（日本時間）。日の区切りは日本時間で固定している")
    val today: LocalDate,
)
