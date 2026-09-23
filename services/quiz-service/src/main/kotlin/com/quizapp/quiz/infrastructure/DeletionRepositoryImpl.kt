package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.DeletedItem
import com.quizapp.quiz.domain.DeletedItemNotFound
import com.quizapp.quiz.domain.DeletionImpact
import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.RestoreBlocked
import com.quizapp.quiz.domain.RestoreObstacle
import com.quizapp.quiz.domain.Trash
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 論理削除と復活の SQL。
 *
 * テナントの絞り込みは書いていない。行レベルセキュリティが弾くため
 * （[com.quizapp.tenant.TenantTransaction] を通した呼び出しが前提）。
 * 他のリポジトリと同じ方針にしている。
 */
@Component
class DeletionRepositoryImpl(private val jdbc: NamedParameterJdbcTemplate) : DeletionRepository {

    override fun impactOfCategory(categoryId: UUID): DeletionImpact? = jdbc.query(
        """
        SELECT
          (SELECT count(*) FROM quiz.difficulties
            WHERE category_id = :id AND deleted_at IS NULL) AS difficulty_count,
          (SELECT count(*) FROM quiz.quizzes
            WHERE category_id = :id AND deleted_at IS NULL) AS quiz_count
        WHERE EXISTS (SELECT 1 FROM quiz.categories WHERE id = :id AND deleted_at IS NULL)
        """,
        mapOf("id" to categoryId),
    ) { rs, _ -> DeletionImpact(rs.getInt("difficulty_count"), rs.getInt("quiz_count")) }.firstOrNull()

    override fun impactOfDifficulty(categoryId: UUID, difficultyId: UUID): DeletionImpact? = jdbc.query(
        """
        SELECT
          (SELECT count(*) FROM quiz.quizzes
            WHERE difficulty_id = :id AND deleted_at IS NULL) AS quiz_count
        WHERE EXISTS (
            SELECT 1 FROM quiz.difficulties
            WHERE id = :id AND category_id = :categoryId AND deleted_at IS NULL
        )
        """,
        mapOf("id" to difficultyId, "categoryId" to categoryId),
    ) { rs, _ -> DeletionImpact(quizCount = rs.getInt("quiz_count")) }.firstOrNull()

    /**
     * 配下から先に消す。
     *
     * 親から消しても結果は同じだが、途中で失敗したときに
     * 「親だけ消えて子が残る」状態になりうる順序を選ばない。
     */
    override fun deleteCategory(categoryId: UUID): Boolean {
        val batch = UUID.randomUUID()
        val params = mapOf("id" to categoryId, "batch" to batch)

        jdbc.update(
            """
            UPDATE quiz.quizzes SET deleted_at = now(), deletion_batch_id = :batch
            WHERE category_id = :id AND deleted_at IS NULL
            """,
            params,
        )
        jdbc.update(
            """
            UPDATE quiz.difficulties SET deleted_at = now(), deletion_batch_id = :batch
            WHERE category_id = :id AND deleted_at IS NULL
            """,
            params,
        )
        return jdbc.update(
            """
            UPDATE quiz.categories SET deleted_at = now(), deletion_batch_id = :batch
            WHERE id = :id AND deleted_at IS NULL
            """,
            params,
        ) > 0
    }

    override fun deleteDifficulty(difficultyId: UUID): Boolean {
        val batch = UUID.randomUUID()
        val params = mapOf("id" to difficultyId, "batch" to batch)

        // 難易度を失ったクイズは出題も編集もできない。一緒に消す
        jdbc.update(
            """
            UPDATE quiz.quizzes SET deleted_at = now(), deletion_batch_id = :batch
            WHERE difficulty_id = :id AND deleted_at IS NULL
            """,
            params,
        )
        return jdbc.update(
            """
            UPDATE quiz.difficulties SET deleted_at = now(), deletion_batch_id = :batch
            WHERE id = :id AND deleted_at IS NULL
            """,
            params,
        ) > 0
    }

    override fun deleteQuiz(quizId: UUID): Boolean = jdbc.update(
        """
        UPDATE quiz.quizzes SET deleted_at = now(), deletion_batch_id = :batch
        WHERE id = :id AND deleted_at IS NULL
        """,
        mapOf("id" to quizId, "batch" to UUID.randomUUID()),
    ) > 0

    override fun listDeleted(): Trash = Trash(
        categories = jdbc.query(
            """
            SELECT id, name, deleted_at FROM quiz.categories
            WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC, name
            """,
        ) { rs, _ ->
            DeletedItem(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                deletedAt = rs.getTimestamp("deleted_at").toInstant(),
                // カテゴリには親がいない。名前の衝突は復活してみるまで分からない
                restorable = true,
            )
        },
        difficulties = jdbc.query(
            """
            SELECT d.id, d.name, c.name AS category_name, d.deleted_at,
                   (c.deleted_at IS NULL) AS restorable
            FROM quiz.difficulties d JOIN quiz.categories c ON c.id = d.category_id
            WHERE d.deleted_at IS NOT NULL ORDER BY d.deleted_at DESC, d.name
            """,
        ) { rs, _ -> rs.toDeletedItem(rs.getString("name")) },
        quizzes = jdbc.query(
            """
            SELECT q.id, q.question, c.name AS category_name, q.deleted_at,
                   (c.deleted_at IS NULL AND d.deleted_at IS NULL) AS restorable
            FROM quiz.quizzes q
            JOIN quiz.categories c ON c.id = q.category_id
            JOIN quiz.difficulties d ON d.id = q.difficulty_id
            WHERE q.deleted_at IS NOT NULL ORDER BY q.deleted_at DESC
            """,
        ) { rs, _ -> rs.toDeletedItem(rs.getString("question")) },
    )

    override fun restoreCategory(categoryId: UUID) {
        val batch = batchOf("quiz.categories", categoryId)

        val restored = restoring(RestoreObstacle.NAME_TAKEN, "同じ名前のカテゴリがすでにあります") {
            jdbc.update(
                """
                UPDATE quiz.categories SET deleted_at = NULL, deletion_batch_id = NULL
                WHERE id = :id AND deleted_at IS NOT NULL
                """,
                mapOf("id" to categoryId),
            )
        }
        if (restored == 0) throw DeletedItemNotFound(categoryId)
        if (batch == null) return

        val params = mapOf("id" to categoryId, "batch" to batch)
        restoring(RestoreObstacle.NAME_TAKEN, "同じ名前の難易度がすでにあります") {
            jdbc.update(
                """
                UPDATE quiz.difficulties SET deleted_at = NULL, deletion_batch_id = NULL
                WHERE category_id = :id AND deleted_at IS NOT NULL AND deletion_batch_id = :batch
                """,
                params,
            )
        }
        jdbc.update(
            """
            UPDATE quiz.quizzes SET deleted_at = NULL, deletion_batch_id = NULL
            WHERE category_id = :id AND deleted_at IS NOT NULL AND deletion_batch_id = :batch
            """,
            params,
        )
    }

    override fun restoreDifficulty(difficultyId: UUID) {
        requireLivingParents(
            """
            SELECT c.deleted_at IS NULL FROM quiz.difficulties d
            JOIN quiz.categories c ON c.id = d.category_id WHERE d.id = :id
            """,
            difficultyId,
            "先にカテゴリを復活させてください",
        )
        val batch = batchOf("quiz.difficulties", difficultyId)

        val restored = restoring(RestoreObstacle.NAME_TAKEN, "同じ名前の難易度がすでにあります") {
            jdbc.update(
                """
                UPDATE quiz.difficulties SET deleted_at = NULL, deletion_batch_id = NULL
                WHERE id = :id AND deleted_at IS NOT NULL
                """,
                mapOf("id" to difficultyId),
            )
        }
        if (restored == 0) throw DeletedItemNotFound(difficultyId)
        if (batch == null) return

        // 同じ操作で消えたクイズだけを戻す。
        // 難易度より前に個別で消されていたクイズは、別のバッチなので戻らない
        jdbc.update(
            """
            UPDATE quiz.quizzes SET deleted_at = NULL, deletion_batch_id = NULL
            WHERE difficulty_id = :id AND deleted_at IS NOT NULL AND deletion_batch_id = :batch
            """,
            mapOf("id" to difficultyId, "batch" to batch),
        )
    }

    override fun restoreQuiz(quizId: UUID) {
        requireLivingParents(
            """
            SELECT c.deleted_at IS NULL AND d.deleted_at IS NULL FROM quiz.quizzes q
            JOIN quiz.categories c ON c.id = q.category_id
            JOIN quiz.difficulties d ON d.id = q.difficulty_id
            WHERE q.id = :id
            """,
            quizId,
            "先にカテゴリと難易度を復活させてください",
        )
        val restored = jdbc.update(
            """
            UPDATE quiz.quizzes SET deleted_at = NULL, deletion_batch_id = NULL
            WHERE id = :id AND deleted_at IS NOT NULL
            """,
            mapOf("id" to quizId),
        )
        if (restored == 0) throw DeletedItemNotFound(quizId)
    }

    /** 削除前に消されたものはバッチを持たない。その場合は自分だけを戻す。 */
    private fun batchOf(table: String, id: UUID): UUID? = jdbc.query(
        "SELECT deletion_batch_id FROM $table WHERE id = :id AND deleted_at IS NOT NULL",
        mapOf("id" to id),
    ) { rs, _ -> rs.getObject("deletion_batch_id", UUID::class.java) }.firstOrNull()

    private fun requireLivingParents(sql: String, id: UUID, message: String) {
        val alive = jdbc.query(sql, mapOf("id" to id)) { rs, _ -> rs.getBoolean(1) }.firstOrNull()
            ?: throw DeletedItemNotFound(id)
        if (!alive) throw RestoreBlocked(RestoreObstacle.PARENT_DELETED, message)
    }

    /**
     * ユニーク制約は生存行だけを対象にしている（ADR-0007）。
     * **削除したあとに同じ名前で作り直されていると、復活で衝突する。**
     * 制約違反をそのまま返さず、何が起きたか分かる形に translate する。
     */
    private fun <T> restoring(obstacle: RestoreObstacle, message: String, block: () -> T): T = try {
        block()
    } catch (e: DuplicateKeyException) {
        throw RestoreBlocked(obstacle, message, e)
    }

    private fun java.sql.ResultSet.toDeletedItem(name: String) = DeletedItem(
        id = getObject("id", UUID::class.java),
        name = name,
        categoryName = getString("category_name"),
        deletedAt = getTimestamp("deleted_at").toInstant(),
        restorable = getBoolean("restorable"),
    )
}
