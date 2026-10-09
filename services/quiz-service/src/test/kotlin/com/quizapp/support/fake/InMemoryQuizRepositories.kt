package com.quizapp.support.fake

import com.quizapp.quiz.domain.AnsweredQuizzes
import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.CategorySummary
import com.quizapp.quiz.domain.DeletedItemNotFound
import com.quizapp.quiz.domain.DeletionImpact
import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.quiz.domain.FigureKind
import com.quizapp.quiz.domain.FigureRepository
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.quiz.domain.Trash
import java.util.UUID

/*
 * quiz モジュールのリポジトリのメモリ実装。
 *
 * テナントも論理削除も区別しない。どちらも SQL の責務で、API テストが確かめる。
 * ここで模すのは「ID で引けるか」「保存すると ID が振られるか」だけ。
 */

class InMemoryCategoryRepository : CategoryRepository {
    private val rows = linkedMapOf<UUID, Category>()

    override fun findAll(): List<Category> = rows.values.toList()

    /** クイズの数は SQL の集計の責務。ここでは 0 を返す */
    override fun findAllSummaries(): List<CategorySummary> = rows.values.map { CategorySummary(it, 0, 0) }

    override fun findById(id: UUID): Category? = rows[id]

    override fun save(category: Category): Category {
        val saved = if (category.id == null) category.copy(id = UUID.randomUUID()) else category
        rows[requireNotNull(saved.id)] = saved
        return saved
    }

    override fun reorder(ids: List<UUID>) {
        ids.forEachIndexed { index, id -> rows.computeIfPresent(id) { _, row -> row.copy(sortOrder = index) } }
    }
}

class InMemoryDifficultyRepository : DifficultyRepository {
    private val rows = linkedMapOf<UUID, Difficulty>()

    override fun findByCategoryId(categoryId: UUID): List<Difficulty> = rows.values.filter {
        it.categoryId == categoryId
    }

    override fun findById(id: UUID): Difficulty? = rows[id]

    override fun save(difficulty: Difficulty): Difficulty {
        val saved = if (difficulty.id == null) difficulty.copy(id = UUID.randomUUID()) else difficulty
        rows[requireNotNull(saved.id)] = saved
        return saved
    }

    override fun reorder(ids: List<UUID>) {
        ids.forEachIndexed { index, id -> rows.computeIfPresent(id) { _, row -> row.copy(sortOrder = index) } }
    }
}

/**
 * 候補は登録順に返す。本物はレベル順・並び順・作成順で返すが、並べ替えは SQL の責務なので模さない。
 * レベルでの絞り込みも同じ理由で扱わない。
 */
class InMemoryQuizRepository : QuizRepository {
    private val rows = linkedMapOf<UUID, Quiz>()

    override fun search(categoryId: UUID?, difficultyId: UUID?, status: QuizStatus?): List<Quiz> = rows.values.filter {
        (categoryId == null || it.categoryId == categoryId) &&
            (difficultyId == null || it.difficultyId == difficultyId) &&
            (status == null || it.status == status)
    }

    override fun findById(id: UUID): Quiz? = rows[id]

    override fun findPublishedCandidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz> {
        require(level == null) { "レベルでの絞り込みは SQL の責務。このフェイクでは扱わない" }
        return search(categoryId, difficultyId, QuizStatus.PUBLISHED)
    }

    override fun findPublishedByIds(ids: List<UUID>): List<Quiz> =
        ids.mapNotNull { rows[it] }.filter { it.status == QuizStatus.PUBLISHED }

    override fun save(quiz: Quiz): Quiz {
        val saved = quiz.copy(
            id = quiz.id ?: UUID.randomUUID(),
            choices = quiz.choices.map { it.copy(id = it.id ?: UUID.randomUUID()) },
        )
        rows[requireNotNull(saved.id)] = saved
        return saved
    }
}

/** 図の行。置いた ID を覚えておくだけ。別テナントの図が見えないことは、行レベルセキュリティの責務で API テストが見る */
class InMemoryFigureRepository : FigureRepository {
    private val kinds = mutableMapOf<UUID, FigureKind>()

    override fun add(id: UUID, kind: FigureKind) {
        kinds[id] = kind
    }

    override fun findKind(id: UUID): FigureKind? = kinds[id]

    override fun findExisting(ids: Collection<UUID>): Set<UUID> = ids.filter { it in kinds }.toSet()

    override fun delete(id: UUID): Boolean = kinds.remove(id) != null
}

class FakeAnsweredQuizzes : AnsweredQuizzes {
    private val answered = mutableSetOf<Pair<UUID, UUID>>()

    fun answer(userId: UUID, quizId: UUID) {
        answered += userId to quizId
    }

    override fun filterAnswered(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID> =
        candidateQuizIds.filter { (userId to it) in answered }.toSet()
}

/**
 * 削除した ID を記録するだけの [DeletionRepository]。
 *
 * 連鎖削除と復活は SQL で組み立てているため、単体テストの対象にしない（API テストが確かめる）。
 * ユースケースが「消す前に何を確かめるか」を見るために、呼ばれたかどうかだけを残す。
 */
class RecordingDeletionRepository : DeletionRepository {
    val deleted = mutableListOf<UUID>()

    override fun impactOfCategory(categoryId: UUID): DeletionImpact = DeletionImpact()

    override fun impactOfDifficulty(categoryId: UUID, difficultyId: UUID): DeletionImpact = DeletionImpact()

    override fun deleteCategory(categoryId: UUID): Boolean = deleted.add(categoryId)

    override fun deleteDifficulty(difficultyId: UUID): Boolean = deleted.add(difficultyId)

    override fun deleteQuiz(quizId: UUID): Boolean = deleted.add(quizId)

    override fun listDeleted(): Trash = Trash()

    override fun restoreCategory(categoryId: UUID) = throw DeletedItemNotFound(categoryId)

    override fun restoreDifficulty(difficultyId: UUID) = throw DeletedItemNotFound(difficultyId)

    override fun restoreQuiz(quizId: UUID) = throw DeletedItemNotFound(quizId)
}
