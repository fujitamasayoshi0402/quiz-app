package com.quizapp.answer.domain

import java.util.UUID

private const val WEEK_DAYS = 7
private const val MONTH_DAYS = 30

/**
 * ランキングを数える期間。**今日からさかのぼった日数で区切る。**
 *
 * 暦の週や月で区切ると、境目をどのタイムゾーンで引くかを決める必要があり、
 * 境目の直後に全員が 0 に戻る。テナントはタイムゾーンを持っていない。
 */
enum class RankingPeriod(val value: String, val days: Int?) {
    WEEK("7d", WEEK_DAYS),
    MONTH("30d", MONTH_DAYS),
    ALL("all", null),
    ;

    companion object {
        fun from(value: String): RankingPeriod = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("期間は 7d / 30d / all を指定してください: $value")
    }
}

/**
 * ランキングに載せる名前。本人がテナントごとに決める。
 *
 * 前後の空白は落とす。見た目で区別できない名前が並ばないよう、重複の判定は DB が大文字と小文字を区別せずに行う。
 */
@JvmInline
value class RankingName private constructor(val value: String) {
    companion object {
        const val MAX_LENGTH = 20

        fun of(raw: String): RankingName {
            val name = raw.trim()
            require(name.isNotEmpty()) { "名前を入力してください" }
            require(name.length <= MAX_LENGTH) { "名前は $MAX_LENGTH 文字以内で入力してください" }
            return RankingName(name)
        }
    }
}

/**
 * 参加者 1 人の成績と順位。
 *
 * [correctCount] は期間内に正解したクイズの数、[answeredCount] は期間内に解いたクイズの数。
 * どちらも同じクイズは 1 回だけ、期間内で最新の回答で数える（履歴の正答率と同じ数え方）。
 * 同点は同じ順位になり、次の順位は人数分とぶ（1, 2, 2, 4）。
 */
data class RankedScore(
    val userId: UUID,
    val name: String,
    val rank: Int,
    val correctCount: Int,
    val answeredCount: Int,
)

/**
 * ランキングへの参加と、その集計。**テナントは行レベルセキュリティが絞る。**
 */
interface RankingRepository {

    /** 参加していなければ null。 */
    fun findName(userId: UUID): String?

    /** 参加する。すでに参加していれば名前を変える。同じテナントに同じ名前があれば [DuplicateRankingNameException]。 */
    fun participate(userId: UUID, name: RankingName)

    /** 参加をやめる。参加していなくても失敗しない。 */
    fun leave(userId: UUID)

    fun findParticipantIds(): List<UUID>

    /**
     * [userIds] の人を、[quizIds] のクイズへの回答で順位づけする。
     *
     * [days] が null なら全期間。期間内に 1 問も解いていない人は返さない。順位の順に返す。
     */
    fun rank(userIds: Collection<UUID>, quizIds: Collection<UUID>, days: Int?): List<RankedScore>
}

class DuplicateRankingNameException(cause: Throwable? = null) : RuntimeException("その名前は、このテナントですでに使われています", cause)

/**
 * テナントへの所属。**実装は auth に置く。**
 *
 * 参加の行は answer にあり、所属は core にある。ADR-0004 がモジュールをまたぐ結合を禁止しているため、
 * [QuizCatalog] と同じく、呼ぶ側がインターフェースを持つ。
 */
interface TenantMembers {

    /**
     * 指定した利用者のうち、いまテナントに所属している人。
     *
     * 候補を渡して絞る形にしているのは、所属者の一覧を answer に渡さないため。
     */
    fun filterMembers(userIds: Collection<UUID>): Set<UUID>
}
