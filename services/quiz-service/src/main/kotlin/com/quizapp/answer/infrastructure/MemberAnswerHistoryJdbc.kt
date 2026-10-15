package com.quizapp.answer.infrastructure

import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.domain.MemberAnswerHistory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [MemberAnswerHistory] の実装。退会する人の履歴を持つ answer が、自分の表を消す（ADR-0030）。
 *
 * テナントは行レベルセキュリティが絞るが、条件にも書く。設定が漏れたときに、ほかのテナントの履歴まで消しにいかない
 */
@Component
class MemberAnswerHistoryJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : MemberAnswerHistory {

    override fun eraseOf(userId: UUID) {
        val params = mapOf("tenantId" to TenantContext.require(), "userId" to userId)
        // 出題（attempt_quizzes）と回答（answers）は、外部キーの ON DELETE CASCADE で一緒に消える
        jdbcTemplate.update(
            "DELETE FROM answer.attempts WHERE tenant_id = :tenantId AND user_id = :userId",
            params,
        )
        jdbcTemplate.update(
            "DELETE FROM answer.ranking_entries WHERE tenant_id = :tenantId AND user_id = :userId",
            params,
        )
        // 「あとで見直す」印も、そのテナントでの本人の記録として消す（DEV-159）
        jdbcTemplate.update(
            "DELETE FROM answer.bookmarks WHERE tenant_id = :tenantId AND user_id = :userId",
            params,
        )
    }
}
