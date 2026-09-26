package com.quizapp.support.fake

import com.quizapp.answer.domain.DuplicateRankingNameException
import com.quizapp.answer.domain.RankedScore
import com.quizapp.answer.domain.RankingName
import com.quizapp.answer.domain.RankingRepository
import com.quizapp.answer.domain.TenantMembers
import java.util.UUID

/**
 * メモリ上の [RankingRepository]。
 *
 * 順位の付け方（期間内で最新の回答、同点の扱い）は SQL の責務なので API テストが見る。
 * ここでは、参加者ごとの成績を [scores] に並べた順で持たせ、渡された利用者の分だけを返す。
 */
class FakeRankingRepository : RankingRepository {

    private val names = linkedMapOf<UUID, String>()

    /** 順位の順に並べる。[rank] は渡された利用者の中で振り直す */
    val scores = mutableListOf<Pair<UUID, Int>>()

    override fun findName(userId: UUID): String? = names[userId]

    override fun participate(userId: UUID, name: RankingName) {
        if (names.any { (id, used) -> id != userId && used.equals(name.value, ignoreCase = true) }) {
            throw DuplicateRankingNameException()
        }
        names[userId] = name.value
    }

    override fun leave(userId: UUID) {
        names.remove(userId)
    }

    override fun findParticipantIds(): List<UUID> = names.keys.toList()

    override fun rank(userIds: Collection<UUID>, quizIds: Collection<UUID>, days: Int?): List<RankedScore> = scores
        .filter { (userId, _) -> userId in userIds && userId in names }
        .mapIndexed { index, (userId, correct) ->
            RankedScore(userId, requireNotNull(names[userId]), index + 1, correct, correct)
        }
}

/** 所属する人を持つ [TenantMembers]。外れた人は [members] から消す */
class FakeTenantMembers : TenantMembers {
    val members = mutableSetOf<UUID>()

    override fun filterMembers(userIds: Collection<UUID>): Set<UUID> = userIds.filter { it in members }.toSet()
}
