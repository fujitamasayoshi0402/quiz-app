package com.quizapp.quiz.infrastructure.integrity

import com.quizapp.quiz.domain.FigureReferences
import com.quizapp.quiz.domain.Quiz
import com.quizapp.tenant.TenantSession
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 決まり（[IntegrityRule]）を SQL で当てる。
 *
 * 利用者の要求の外で動くため、テナントの文脈（`TenantContext`）を持たない。テナントはここで設定する。
 * **読むだけ。** 読み取り専用のトランザクションで流し、崩れたものを直さない。直し方は崩れ方による
 */
@Component
class IntegrityChecksJdbc(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val tenantSession: TenantSession,
) : IntegrityChecks {

    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    // core.tenants は行レベルセキュリティの外にある（V5）
    override fun tenantIds(): List<UUID> =
        jdbcTemplate.queryForList("SELECT id FROM core.tenants ORDER BY id", UUID::class.java).filterNotNull()

    override fun check(tenantId: UUID): List<IntegrityViolation> = requireNotNull(
        readOnly.execute {
            tenantSession.apply(tenantId)
            val quizzes = liveQuizzes()
            (quizzes.flatMap { it.violations() } + missingFigures(quizzes) + aliveUnderDeletedParent())
                .map { (rule, id) -> IntegrityViolation(rule, tenantId, id) }
        },
    )

    private fun liveQuizzes(): List<QuizRow> = jdbcTemplate.query(
        """
        SELECT q.id, q.status, q.explanation,
               count(c.id) AS choices, count(c.id) FILTER (WHERE c.is_correct) AS correct
        FROM quiz.quizzes q
        LEFT JOIN quiz.choices c ON c.quiz_id = q.id
        WHERE q.deleted_at IS NULL
        GROUP BY q.id
        """.trimIndent(),
    ) { rs, _ ->
        QuizRow(
            id = rs.getObject("id", UUID::class.java),
            published = rs.getString("status") == PUBLISHED,
            explanation = rs.getString("explanation"),
            choices = rs.getInt("choices"),
            correct = rs.getInt("correct"),
        )
    }

    // 図の書き方はドメインのものを使う。SQL の正規表現で書き直すと、2 つがずれる
    private fun missingFigures(quizzes: List<QuizRow>): List<Pair<IntegrityRule, UUID>> {
        val references = quizzes.associate { it.id to FigureReferences.findIn(it.explanation) }
        val referenced = references.values.flatten().toSet()
        if (referenced.isEmpty()) return emptyList()

        val existing = jdbcTemplate.queryForList(
            "SELECT id FROM quiz.figures WHERE id = ANY (?)",
            UUID::class.java,
            referenced.toTypedArray(),
        ).filterNotNull().toSet()
        return references.filterValues { !existing.containsAll(it) }.keys.map { IntegrityRule.MISSING_FIGURE to it }
    }

    private fun aliveUnderDeletedParent(): List<Pair<IntegrityRule, UUID>> = jdbcTemplate.queryForList(
        """
        SELECT q.id FROM quiz.quizzes q
        JOIN quiz.categories c ON c.id = q.category_id
        JOIN quiz.difficulties d ON d.id = q.difficulty_id
        WHERE q.deleted_at IS NULL AND (c.deleted_at IS NOT NULL OR d.deleted_at IS NOT NULL)
        UNION ALL
        SELECT d.id FROM quiz.difficulties d
        JOIN quiz.categories c ON c.id = d.category_id
        WHERE d.deleted_at IS NULL AND c.deleted_at IS NOT NULL
        """.trimIndent(),
        UUID::class.java,
    ).filterNotNull().map { IntegrityRule.ALIVE_UNDER_DELETED_PARENT to it }

    private data class QuizRow(
        val id: UUID,
        val published: Boolean,
        val explanation: String,
        val choices: Int,
        val correct: Int,
    ) {
        // 決まりは Quiz の検証と同じ。公開するときだけ揃っていればよく、下書きは数の上限だけを守る
        fun violations(): List<Pair<IntegrityRule, UUID>> = listOfNotNull(
            IntegrityRule.CHOICE_COUNT.takeIf {
                if (published) choices != Quiz.CHOICE_COUNT else choices > Quiz.CHOICE_COUNT
            },
            IntegrityRule.CORRECT_CHOICE_COUNT.takeIf { published && correct != 1 },
            IntegrityRule.EXPLANATION_BLANK.takeIf { published && explanation.isBlank() },
        ).map { it to id }
    }

    private companion object {
        const val PUBLISHED = "published"
    }
}
