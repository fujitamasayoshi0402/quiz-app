package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.QuizChange
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.support.fake.InMemoryCategoryRepository
import com.quizapp.support.fake.InMemoryDifficultyRepository
import com.quizapp.support.fake.InMemoryFigureRepository
import com.quizapp.support.fake.InMemoryQuizRepository
import com.quizapp.support.fake.RecordingDeletionRepository
import com.quizapp.support.fake.RecordingQuizEventOutbox
import com.quizapp.support.fake.RecordingQuizEventOutbox.Recorded
import com.quizapp.support.fake.UnlimitedTenantCapacity
import com.quizapp.support.fake.fakeTenantTransaction
import com.quizapp.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * どの操作で、どのイベントが Outbox に書かれるか（ADR-0022）。**1 回の操作で 1 つ。**
 *
 * 同じトランザクションで書かれること（失敗したら残らないこと）は、DB を使う `QuizEventOutboxApiTest` が見る。
 */
class QuizEventUseCaseTest {

    private val categories = InMemoryCategoryRepository()
    private val difficulties = InMemoryDifficultyRepository()
    private val quizzes = InMemoryQuizRepository()
    private val figures = InMemoryFigureRepository()
    private val outbox = RecordingQuizEventOutbox()
    private val transaction = fakeTenantTransaction()

    private val quizUseCase =
        QuizUseCase(
            quizzes,
            categories,
            difficulties,
            RecordingDeletionRepository(),
            figures,
            outbox,
            UnlimitedTenantCapacity,
            transaction,
        )
    private val importUseCase = QuizImportUseCase(
        quizzes,
        categories,
        difficulties,
        figures,
        outbox,
        UnlimitedTenantCapacity,
        transaction,
    )

    private lateinit var aws: UUID
    private lateinit var saa: UUID

    @BeforeEach
    fun setUp() {
        TenantContext.set(UUID.randomUUID())
        aws = requireNotNull(categories.save(Category(name = "AWS")).id)
        saa = requireNotNull(difficulties.save(Difficulty(categoryId = aws, name = "SAA", level = 2)).id)
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private val choices = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }

    private fun create(status: QuizStatus = QuizStatus.DRAFT, question: String = "問題文") =
        quizUseCase.create(aws, saa, question, "解説", choices, status)

    private fun update(id: UUID, status: QuizStatus, question: String = "問題文") =
        quizUseCase.update(id, aws, saa, question, "解説", choices, status)

    private fun changes() = outbox.events.map { (it as Recorded.Changed).change }

    @Test
    @DisplayName("作ると、下書きでも公開でも作成のイベントが書かれる")
    fun createWritesCreated() {
        val draft = create()
        val published = create(QuizStatus.PUBLISHED, question = "別の問題文")

        assertThat(outbox.events).containsExactly(
            Recorded.Changed(QuizChange.CREATED, draft),
            Recorded.Changed(QuizChange.CREATED, published),
        )
    }

    @Test
    @DisplayName("保存のたびに、変化に応じたイベントが 1 つだけ書かれる")
    fun updateWritesOneEventPerSave() {
        val id = requireNotNull(create().id)
        outbox.events.clear()

        update(id, QuizStatus.DRAFT, question = "書き換えた問題文")
        update(id, QuizStatus.PUBLISHED, question = "公開と同時に書き換えた問題文")
        update(id, QuizStatus.DRAFT, question = "公開と同時に書き換えた問題文")

        assertThat(changes()).containsExactly(QuizChange.UPDATED, QuizChange.PUBLISHED, QuizChange.UNPUBLISHED)
    }

    @Test
    @DisplayName("何も変えずに保存したときは、何も書かれない")
    fun unchangedSaveWritesNothing() {
        val id = requireNotNull(create(QuizStatus.PUBLISHED).id)
        outbox.events.clear()

        update(id, QuizStatus.PUBLISHED)

        assertThat(outbox.events).isEmpty()
    }

    @Test
    @DisplayName("書かれるのは保存したあとのクイズ。ID が振られている")
    fun recordsSavedQuiz() {
        val id = requireNotNull(create().id)
        outbox.events.clear()

        update(id, QuizStatus.PUBLISHED)

        val recorded = outbox.events.single() as Recorded.Changed
        assertThat(recorded.quiz.id).isEqualTo(id)
        assertThat(recorded.quiz.status).isEqualTo(QuizStatus.PUBLISHED)
    }

    @Test
    @DisplayName("失敗した操作では、何も書かれない")
    fun failedOperationWritesNothing() {
        assertThatThrownBy { quizUseCase.create(UUID.randomUUID(), saa, "問題文", "解説", choices, QuizStatus.DRAFT) }
            .isInstanceOf(CategoryNotFoundException::class.java)
        assertThatThrownBy { update(UUID.randomUUID(), QuizStatus.DRAFT) }
            .isInstanceOf(QuizNotFoundException::class.java)

        assertThat(outbox.events).isEmpty()
    }

    @Test
    @DisplayName("消しても、何も書かれない")
    fun deleteWritesNothing() {
        val id = requireNotNull(create().id)
        outbox.events.clear()

        quizUseCase.delete(id)

        assertThat(outbox.events).isEmpty()
    }

    @Test
    @DisplayName("一括インポートは、件数だけのイベントが 1 つ書かれる。1 件ずつは書かれない")
    fun importWritesOneEvent() {
        importUseCase.import(
            listOf(
                row("1 問目", "published"),
                row("2 問目", "draft"),
                row("3 問目", "published"),
            ),
        )

        assertThat(outbox.events).containsExactly(Recorded.Imported(total = 3, published = 2))
    }

    @Test
    @DisplayName("取り込めない行があれば、何も書かれない")
    fun rejectedImportWritesNothing() {
        assertThatThrownBy { importUseCase.import(listOf(row("1 問目", "published"), row("2 問目", "公開"))) }
            .isInstanceOf(QuizImportRejectedException::class.java)

        assertThat(outbox.events).isEmpty()
    }

    private fun row(question: String, status: String) = QuizImportRow(
        category = "AWS",
        difficulty = "SAA",
        question = question,
        explanation = "解説",
        choices = choices.map { QuizImportRow.ChoiceInput(it.body, it.isCorrect) },
        status = status,
    )
}
