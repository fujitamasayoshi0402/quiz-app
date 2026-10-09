package com.quizapp.answer.usecase

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.answer.domain.RankedScore
import com.quizapp.answer.domain.RankingName
import com.quizapp.answer.domain.RankingPeriod
import com.quizapp.answer.domain.RankingRepository
import com.quizapp.answer.domain.TenantMembers
import com.quizapp.auth.UserContext
import com.quizapp.tenant.TenantTransaction
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * テナント内のランキング。
 *
 * - **載るのは、参加を選んで名前を決めた人だけ。** 参加していない人も見ることはできる
 * - 指標は、期間内に正解したクイズの数。同じクイズの解き直しでは増えず、1 問だけ解いて上位に来ることもない
 * - 数えるのは、いま出題できるクイズへの、期間内で最新の回答（履歴の正答率と同じ数え方）
 *
 * **他の人の利用者 ID は返さない。** 表に要るのは名前と成績だけで、自分の行かどうかはサーバーが印を付ける。
 */
@Service
class RankingUseCase(
    private val repository: RankingRepository,
    private val members: TenantMembers,
    private val catalog: QuizCatalog,
    private val tenantTransaction: TenantTransaction,
) {
    fun ranking(period: RankingPeriod): RankingView = tenantTransaction.execute {
        val me = UserContext.require()
        // 参加の行は所属が外れても残る。所属は answer の外にあるので、載せる前に突き合わせる
        val participants = members.filterMembers(repository.findParticipantIds())
        val scores = repository.rank(participants, catalog.findPlayableQuizIds(), period.days)

        RankingView(
            period = period.value,
            entries = scores.take(MAX_ENTRIES).map { it.toEntry(me) },
            me = MyRanking(
                name = repository.findName(me),
                // 表の外（下位）にいても、自分の順位は分かるようにする
                entry = scores.firstOrNull { it.userId == me }?.toEntry(me),
            ),
        )
    }

    /** 参加する。参加していれば名前を変える。 */
    fun participate(name: String): Participation = tenantTransaction.execute {
        val rankingName = RankingName.of(name)
        repository.participate(UserContext.require(), rankingName)
        Participation(rankingName.value)
    }

    fun leave() = tenantTransaction.executeWithoutResult { repository.leave(UserContext.require()) }

    private fun RankedScore.toEntry(me: UUID) = RankingEntry(
        rank = rank,
        name = name,
        correctCount = correctCount,
        answeredCount = answeredCount,
        isMe = userId == me,
    )

    companion object {
        /** 表に並べる数。テナントの参加者がこれを超えても、自分の順位は [MyRanking] で返す */
        const val MAX_ENTRIES = 100
    }
}

data class RankingView(
    /** `7d` / `30d` / `all` */
    val period: String,
    val entries: List<RankingEntry>,
    val me: MyRanking,
)

/** 正答率は [correctCount] / [answeredCount]。丸めはクライアントが決める。 */
data class RankingEntry(
    val rank: Int,
    val name: String,
    val correctCount: Int,
    val answeredCount: Int,
    // Kotlin の `isMe` は Java の getter 規約では `me` と読まれる。
    // Jackson は Kotlin のプロパティ名で出すため、明示しないと定義と実際の JSON がずれる
    @get:Schema(name = "isMe")
    val isMe: Boolean,
)

/**
 * 自分の参加の状態。
 *
 * [name] が null なら参加していない。参加していても、期間内に 1 問も解いていなければ [entry] は null。
 */
data class MyRanking(val name: String?, val entry: RankingEntry?)

data class Participation(val name: String)
