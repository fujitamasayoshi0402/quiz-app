package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.DuplicateRankingNameException
import com.quizapp.answer.domain.RankedScore
import com.quizapp.answer.domain.RankingName
import com.quizapp.answer.domain.RankingRepository
import com.quizapp.tenant.TenantContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [RankingRepository] の実装。answer スキーマだけを読む。
 *
 * 順位は表示のたびに SQL で数える（履歴と同じ方針。[AttemptHistoryJdbc]）。
 * テナントの参加者は多くても数百人で、回答は利用者とクイズの索引（`answers_user_quiz_idx`）で絞れる。
 */
@Component
class RankingJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : RankingRepository {

    override fun findName(userId: UUID): String? = jdbcTemplate.query(
        "SELECT name FROM answer.ranking_entries WHERE user_id = :userId",
        mapOf("userId" to userId),
    ) { rs, _ -> rs.getString("name") }.firstOrNull()

    override fun participate(userId: UUID, name: RankingName) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO answer.ranking_entries (tenant_id, user_id, name) VALUES (:tenantId, :userId, :name)
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET name = EXCLUDED.name
                """,
                mapOf("tenantId" to TenantContext.require(), "userId" to userId, "name" to name.value),
            )
        } catch (e: DuplicateKeyException) {
            // 名前の一意索引。ON CONFLICT が受け止めるのは主キーの衝突だけなので、名前の衝突はここに来る。
            // 先に SELECT で確かめる形にすると、同時に同じ名前で参加したときに抜ける
            throw DuplicateRankingNameException(e)
        }
    }

    override fun leave(userId: UUID) {
        jdbcTemplate.update("DELETE FROM answer.ranking_entries WHERE user_id = :userId", mapOf("userId" to userId))
    }

    override fun findParticipantIds(): List<UUID> = jdbcTemplate.query(
        "SELECT user_id FROM answer.ranking_entries",
        emptyMap<String, Any>(),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

    /**
     * 利用者とクイズの組ごとに期間内で最新の回答を選び、利用者ごとに数えて順位を付ける。
     *
     * 順位は `rank()` で付ける。正解の数が同じなら正答率の高いほうが上で、それも同じなら同じ順位。
     * 同じ順位の中の並びは名前の順にして、表示のたびに入れ替わらないようにする。
     */
    override fun rank(userIds: Collection<UUID>, quizIds: Collection<UUID>, days: Int?): List<RankedScore> {
        // IN 句に空のリストを渡すと SQL が壊れる
        if (userIds.isEmpty() || quizIds.isEmpty()) return emptyList()

        val params = mutableMapOf<String, Any>("userIds" to userIds, "quizIds" to quizIds)
        val since = days?.let {
            params["days"] = it
            "AND a.answered_at >= now() - make_interval(days => :days)"
        }.orEmpty()

        return jdbcTemplate.query(
            """
            WITH latest AS (
                SELECT DISTINCT ON (a.user_id, a.quiz_id) a.user_id, a.is_correct
                FROM answer.answers a
                WHERE a.user_id IN (:userIds) AND a.quiz_id IN (:quizIds) $since
                ORDER BY a.user_id, a.quiz_id, a.answered_at DESC, a.id DESC
            ), scores AS (
                SELECT user_id,
                       count(*) FILTER (WHERE is_correct) AS correct_count,
                       count(*) AS answered_count
                FROM latest
                GROUP BY user_id
            )
            SELECT s.user_id, e.name, s.correct_count, s.answered_count,
                   rank() OVER (
                       ORDER BY s.correct_count DESC, s.correct_count::numeric / s.answered_count DESC
                   ) AS rank
            FROM scores s
            JOIN answer.ranking_entries e ON e.user_id = s.user_id
            ORDER BY rank, e.name
            """,
            params,
        ) { rs, _ ->
            RankedScore(
                userId = rs.getObject("user_id", UUID::class.java),
                name = rs.getString("name"),
                rank = rs.getInt("rank"),
                correctCount = rs.getInt("correct_count"),
                answeredCount = rs.getInt("answered_count"),
            )
        }
    }
}
