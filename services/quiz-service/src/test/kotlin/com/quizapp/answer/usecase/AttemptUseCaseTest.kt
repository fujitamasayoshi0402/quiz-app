package com.quizapp.answer.usecase

import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.auth.CurrentUser
import com.quizapp.auth.UserContext
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.support.fake.FakeQuizCatalog
import com.quizapp.support.fake.InMemoryAttemptRepository
import com.quizapp.support.fake.fakeTenantTransaction
import com.quizapp.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 挑戦の開始・回答・完了の単体テスト。
 *
 * 採点と挑戦の状態遷移はここに閉じている。DB を外して、分岐を 1 つずつ確かめる。
 * テナントの分離と、同じクイズへの二重回答の拒否は DB の責務なので API テストが見る。
 */
class AttemptUseCaseTest {

    private val repository = InMemoryAttemptRepository()
    private val catalog = FakeQuizCatalog()
    private val useCase = AttemptUseCase(repository, catalog, fakeTenantTransaction())

    private val user = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        TenantContext.set(UUID.randomUUID())
        UserContext.set(CurrentUser(user))
    }

    @AfterEach
    fun tearDown() {
        // スレッドに残すと、同じスレッドで動く次のテストが前の利用者を引き継ぐ
        TenantContext.clear()
        UserContext.clear()
    }

    private fun startWith(count: Int): Pair<AttemptView, List<DeliveredQuiz>> {
        val quizzes = (1..count).map { catalog.add("問題 $it") }
        return useCase.start(DeliveryCriteria(), discardInProgress = false) to quizzes
    }

    private fun DeliveredQuiz.correct() = choices.first().id

    private fun DeliveredQuiz.wrong() = choices.last().id

    @Nested
    @DisplayName("開始")
    inner class Start {
        @Test
        @DisplayName("出題リストを持つ挑戦が始まる")
        fun startsWithQuizzes() {
            val (attempt, quizzes) = startWith(3)

            assertThat(attempt.status).isEqualTo("in_progress")
            assertThat(attempt.quizzes.map { it.id }).containsExactlyElementsOf(quizzes.map { it.id })
            assertThat(attempt.answeredQuizIds).isEmpty()
        }

        @Test
        @DisplayName("出題できるクイズが無ければ始めない")
        fun noQuiz() {
            assertThatThrownBy { useCase.start(DeliveryCriteria(), discardInProgress = false) }
                .isInstanceOf(NoQuizAvailableException::class.java)
        }

        @Test
        @DisplayName("中断中の挑戦があれば、黙って破棄せず件数を添えて止める")
        fun refusesWhileInProgress() {
            val (attempt, quizzes) = startWith(3)
            useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct())

            assertThatThrownBy { useCase.start(DeliveryCriteria(), discardInProgress = false) }
                .isInstanceOfSatisfying(AttemptInProgressException::class.java) {
                    assertThat(it.summary).isEqualTo(AttemptSummary(attempt.id, totalCount = 3, answeredCount = 1))
                }
        }

        @Test
        @DisplayName("破棄を指定すると、中断中の挑戦を破棄して始め直す")
        fun discardsInProgress() {
            val (old, _) = startWith(2)

            val new = useCase.start(DeliveryCriteria(), discardInProgress = true)

            assertThat(new.id).isNotEqualTo(old.id)
            assertThat(repository.findById(old.id)?.status).isEqualTo(AttemptStatus.ABANDONED)
        }

        @Test
        @DisplayName("テナントが決まっていなければ何もしない")
        fun requiresTenant() {
            catalog.add()
            TenantContext.clear()

            assertThatThrownBy { useCase.start(DeliveryCriteria(), discardInProgress = false) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(repository.findInProgress(user)).isNull()
        }
    }

    @Nested
    @DisplayName("回答")
    inner class Answering {
        @Test
        @DisplayName("正解すると、正誤・正解・解説と進み具合が返る")
        fun correctAnswer() {
            val (attempt, quizzes) = startWith(3)

            val result = useCase.answer(attempt.id, quizzes[1].id, quizzes[1].correct())

            assertThat(result).isEqualTo(
                AnswerResult(
                    isCorrect = true,
                    correctChoiceId = quizzes[1].correct(),
                    explanation = "問題 2 の解説",
                    answeredCount = 1,
                    totalCount = 3,
                ),
            )
        }

        @Test
        @DisplayName("間違えても、正解の選択肢を返す")
        fun wrongAnswer() {
            val (attempt, quizzes) = startWith(1)

            val result = useCase.answer(attempt.id, quizzes[0].id, quizzes[0].wrong())

            assertThat(result.isCorrect).isFalse()
            assertThat(result.correctChoiceId).isEqualTo(quizzes[0].correct())
        }

        @Test
        @DisplayName("この挑戦で出題していないクイズには回答できない")
        fun quizNotInAttempt() {
            val (attempt, _) = startWith(1)
            val other = catalog.add("出題していない問題")

            assertThatThrownBy { useCase.answer(attempt.id, other.id, other.correct()) }
                .isInstanceOf(QuizNotInAttemptException::class.java)
        }

        @Test
        @DisplayName("別のクイズの選択肢では回答できない")
        fun choiceOfAnotherQuiz() {
            val (attempt, quizzes) = startWith(2)

            assertThatThrownBy { useCase.answer(attempt.id, quizzes[0].id, quizzes[1].correct()) }
                .isInstanceOf(InvalidChoiceException::class.java)
        }

        @Test
        @DisplayName("出題後にクイズが編集され、選択肢が変わっていたら受け付けない")
        fun editedAfterDelivery() {
            val (attempt, quizzes) = startWith(1)
            catalog.edit(quizzes[0].id)

            assertThatThrownBy { useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct()) }
                .isInstanceOf(InvalidChoiceException::class.java)
        }

        @Test
        @DisplayName("出題後にクイズが削除されていたら受け付けない")
        fun withdrawnAfterDelivery() {
            val (attempt, quizzes) = startWith(1)
            catalog.withdraw(quizzes[0].id)

            assertThatThrownBy { useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct()) }
                .isInstanceOf(QuizNoLongerAvailableException::class.java)
        }

        @Test
        @DisplayName("終わった挑戦には回答できない")
        fun finishedAttempt() {
            val (attempt, quizzes) = startWith(2)
            useCase.complete(attempt.id)

            assertThatThrownBy { useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct()) }
                .isInstanceOf(AttemptAlreadyFinishedException::class.java)
        }

        @Test
        @DisplayName("他人の挑戦は、存在しないものとして扱う")
        fun anotherUsersAttempt() {
            val (attempt, quizzes) = startWith(1)
            UserContext.set(CurrentUser(UUID.randomUUID()))

            assertThatThrownBy { useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct()) }
                .isInstanceOf(AttemptNotFoundException::class.java)
            assertThatThrownBy { useCase.resume(attempt.id) }
                .isInstanceOf(AttemptNotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("再開")
    inner class Resume {
        @Test
        @DisplayName("出題した順のまま返る。引き直した結果の順序には頼らない")
        fun keepsDeliveryOrder() {
            val (attempt, quizzes) = startWith(3)

            val resumed = useCase.resume(attempt.id)

            assertThat(resumed.quizzes.map { it.id }).containsExactlyElementsOf(quizzes.map { it.id })
        }

        @Test
        @DisplayName("回答済みのクイズが分かる")
        fun answeredQuizIds() {
            val (attempt, quizzes) = startWith(3)
            useCase.answer(attempt.id, quizzes[2].id, quizzes[2].wrong())

            assertThat(useCase.resume(attempt.id).answeredQuizIds).containsExactly(quizzes[2].id)
        }

        @Test
        @DisplayName("削除されたクイズは外し、外した数を返す")
        fun excludesWithdrawn() {
            val (attempt, quizzes) = startWith(3)
            catalog.withdraw(quizzes[1].id)

            val resumed = useCase.resume(attempt.id)

            assertThat(resumed.quizzes.map { it.id }).containsExactly(quizzes[0].id, quizzes[2].id)
            assertThat(resumed.excludedCount).isEqualTo(1)
        }

        @Test
        @DisplayName("編集されたクイズは、新しい選択肢で返る")
        fun returnsEditedChoices() {
            val (attempt, quizzes) = startWith(1)
            val edited = catalog.edit(quizzes[0].id)

            assertThat(useCase.resume(attempt.id).quizzes.single().choices).isEqualTo(edited.choices)
        }

        @Test
        @DisplayName("中断中の挑戦が無ければ null")
        fun noCurrent() {
            assertThat(useCase.current()).isNull()

            val (attempt, _) = startWith(1)
            assertThat(useCase.current()?.id).isEqualTo(attempt.id)
        }
    }

    @Nested
    @DisplayName("完了と破棄")
    inner class Finish {
        @Test
        @DisplayName("正答数と、全問の正誤・解説を出題順に返す。未回答は不正解として並ぶ")
        fun completeReturnsResults() {
            val (attempt, quizzes) = startWith(3)
            useCase.answer(attempt.id, quizzes[0].id, quizzes[0].correct())
            useCase.answer(attempt.id, quizzes[1].id, quizzes[1].wrong())

            val result = useCase.complete(attempt.id)

            assertThat(result.status).isEqualTo("completed")
            assertThat(listOf(result.totalCount, result.answeredCount, result.correctCount)).containsExactly(3, 2, 1)
            assertThat(result.results.map { Triple(it.quizId, it.selectedChoiceId, it.isCorrect) }).containsExactly(
                Triple(quizzes[0].id, quizzes[0].correct(), true),
                Triple(quizzes[1].id, quizzes[1].wrong(), false),
                Triple(quizzes[2].id, null, false),
            )
            assertThat(result.results.map { it.explanation }).containsExactly("問題 1 の解説", "問題 2 の解説", "問題 3 の解説")
        }

        @Test
        @DisplayName("削除されたクイズは結果の一覧から外れる。出題数には数えたまま")
        fun resultsExcludeWithdrawn() {
            val (attempt, quizzes) = startWith(3)
            catalog.withdraw(quizzes[0].id)

            val result = useCase.complete(attempt.id)

            assertThat(result.results.map { it.quizId }).containsExactly(quizzes[1].id, quizzes[2].id)
            assertThat(result.totalCount).isEqualTo(3)
        }

        @Test
        @DisplayName("完了済みの挑戦をもう一度完了しても、結果を返す")
        fun completeIsRepeatable() {
            val (attempt, _) = startWith(1)
            val first = useCase.complete(attempt.id)

            assertThat(useCase.complete(attempt.id)).isEqualTo(first)
        }

        @Test
        @DisplayName("破棄した挑戦は完了できず、もう一度破棄もできない")
        fun abandonedAttempt() {
            val (attempt, _) = startWith(1)
            useCase.abandon(attempt.id)

            assertThat(useCase.current()).isNull()
            assertThatThrownBy { useCase.complete(attempt.id) }
                .isInstanceOf(AttemptAlreadyFinishedException::class.java)
            assertThatThrownBy { useCase.abandon(attempt.id) }
                .isInstanceOf(AttemptAlreadyFinishedException::class.java)
        }
    }
}
