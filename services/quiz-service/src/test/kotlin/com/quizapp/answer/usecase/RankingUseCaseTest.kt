package com.quizapp.answer.usecase

import com.quizapp.answer.domain.RankingName
import com.quizapp.answer.domain.RankingPeriod
import com.quizapp.auth.CurrentUser
import com.quizapp.auth.UserContext
import com.quizapp.support.fake.FakeQuizCatalog
import com.quizapp.support.fake.FakeRankingRepository
import com.quizapp.support.fake.FakeTenantMembers
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
 * ランキングの単体テスト。
 *
 * 誰を載せるか（所属から外れた人を除く）、表の長さと自分の行の返し方、名前の規則を確かめる。
 * 順位の付け方と期間の区切りは SQL の責務なので API テストが見る。
 */
class RankingUseCaseTest {

    private val repository = FakeRankingRepository()
    private val members = FakeTenantMembers()
    private val catalog = FakeQuizCatalog()
    private val useCase = RankingUseCase(repository, members, catalog, fakeTenantTransaction())

    private val me = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        TenantContext.set(UUID.randomUUID())
        UserContext.set(CurrentUser(me))
        members.members += me
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        UserContext.clear()
    }

    /** 参加して、成績を持たせる。[scores] に足した順が順位になる */
    private fun participant(name: String, correct: Int, member: Boolean = true): UUID {
        val id = if (name == "自分") me else UUID.randomUUID()
        if (member) members.members += id
        repository.participate(id, RankingName.of(name))
        repository.scores += id to correct
        return id
    }

    @Nested
    @DisplayName("載せる人")
    inner class Entries {
        @Test
        @DisplayName("所属から外れた人は載せない")
        fun excludesFormerMembers() {
            participant("残っている人", correct = 3)
            participant("外れた人", correct = 5, member = false)

            val view = useCase.ranking(RankingPeriod.MONTH)

            assertThat(view.entries.map { it.name }).containsExactly("残っている人")
            assertThat(view.entries.map { it.rank }).containsExactly(1)
        }

        @Test
        @DisplayName("自分の行に印を付ける。ほかの人の利用者 ID は返さない")
        fun marksOwnEntry() {
            participant("先頭", correct = 5)
            participant("自分", correct = 3)

            val view = useCase.ranking(RankingPeriod.MONTH)

            assertThat(view.entries.map { it.name to it.isMe }).containsExactly("先頭" to false, "自分" to true)
            assertThat(view.me.name).isEqualTo("自分")
            assertThat(view.me.entry?.rank).isEqualTo(2)
        }

        @Test
        @DisplayName("表に入らない順位でも、自分の順位は返す")
        fun returnsOwnRankBeyondTable() {
            repeat(RankingUseCase.MAX_ENTRIES) { participant("参加者 $it", correct = 10) }
            participant("自分", correct = 1)

            val view = useCase.ranking(RankingPeriod.ALL)

            assertThat(view.entries).hasSize(RankingUseCase.MAX_ENTRIES).noneMatch { it.isMe }
            assertThat(view.me.entry?.rank).isEqualTo(RankingUseCase.MAX_ENTRIES + 1)
        }

        @Test
        @DisplayName("参加していなければ、名前も順位も返さない")
        fun notParticipating() {
            participant("ほかの人", correct = 1)

            val view = useCase.ranking(RankingPeriod.WEEK)

            assertThat(view.me.name).isNull()
            assertThat(view.me.entry).isNull()
            assertThat(view.entries.map { it.name }).containsExactly("ほかの人")
        }

        @Test
        @DisplayName("期間を応答に書き戻す")
        fun echoesPeriod() {
            assertThat(RankingPeriod.entries.map { useCase.ranking(it).period }).containsExactly("7d", "30d", "all")
        }
    }

    @Nested
    @DisplayName("参加")
    inner class Participate {
        @Test
        @DisplayName("前後の空白を落として参加し、もう一度呼ぶと名前を変える")
        fun participatesAndRenames() {
            assertThat(useCase.participate("  はじめの名前 ").name).isEqualTo("はじめの名前")
            useCase.participate("変えた名前")

            assertThat(repository.findName(me)).isEqualTo("変えた名前")
        }

        @Test
        @DisplayName("空白だけの名前と、長すぎる名前は入力の誤り")
        fun rejectsInvalidNames() {
            listOf("   ", "あ".repeat(RankingName.MAX_LENGTH + 1)).forEach { name ->
                assertThatThrownBy { useCase.participate(name) }.isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThat(RankingName.of("あ".repeat(RankingName.MAX_LENGTH)).value).hasSize(RankingName.MAX_LENGTH)
        }

        @Test
        @DisplayName("やめると載らなくなる。参加していなくても失敗しない")
        fun leaves() {
            participant("自分", correct = 3)

            useCase.leave()
            useCase.leave()

            assertThat(useCase.ranking(RankingPeriod.MONTH).entries).isEmpty()
        }
    }

    @Test
    @DisplayName("期間は 7d / 30d / all のどれか")
    fun parsesPeriod() {
        assertThat(RankingPeriod.from("7d")).isEqualTo(RankingPeriod.WEEK)
        assertThatThrownBy { RankingPeriod.from("1y") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
