package com.quizapp.quiz.infrastructure.outbox

import com.quizapp.support.MutableClock
import com.quizapp.support.fake.InMemoryEventBus
import com.quizapp.support.fake.InMemoryOutboxRows
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 拾い直し（ADR-0022）。**利用者の要求で DB を使った直後だけ動く**ことが要点。
 * いつも動くと定期的に DB へ接続し、Aurora が一時停止しなくなる。
 *
 * どの行を拾うかの SQL（テナントをまたぐ、ロックした行を飛ばす）は `OutboxDeliveryApiTest` が見る。
 */
class OutboxRelayTest {

    private val clock = MutableClock()
    private val outbox = InMemoryOutboxRows()
    private val bus = InMemoryEventBus()
    private val activity = DatabaseActivity(clock)
    private val properties = EventsProperties.Relay()
    private val relay = OutboxRelay(outbox.rows(clock::instant), bus, activity, properties, clock)

    /** コミットの直後に送れなかった行。拾い直しの対象になる古さにしておく */
    private fun unsent() = outbox.add(occurredAt = clock.instant().minus(Duration.ofMinutes(2)))

    @Test
    @DisplayName("起動してから誰も使っていなければ、DB に触れない")
    fun idleAfterStartup() {
        unsent()

        relay.tick()

        assertThat(outbox.relayCalls).isZero()
        assertThat(bus.published).isEmpty()
    }

    @Test
    @DisplayName("利用者が DB を使ってから 5 分の間は拾い直し、過ぎたら DB に触れない")
    fun runsOnlyWithinWindowAfterUse() {
        activity.record()

        clock.advance(Duration.ofMinutes(5))
        relay.tick()
        assertThat(outbox.relayCalls).isEqualTo(1)

        clock.advance(Duration.ofSeconds(1))
        relay.tick()
        assertThat(outbox.relayCalls).isEqualTo(1)

        // また使われたら、そこから 5 分動く
        activity.record()
        relay.tick()
        assertThat(outbox.relayCalls).isEqualTo(2)
    }

    @Test
    @DisplayName("送れなかったものは残り、次の回に送り直す")
    fun retriesFailures() {
        val first = unsent()
        val second = unsent()
        bus.failing += second.id
        activity.record()

        relay.tick()
        assertThat(outbox.isPublished(first.id)).isTrue()
        assertThat(outbox.isPublished(second.id)).isFalse()

        bus.failing.clear()
        clock.advance(Duration.ofMinutes(1))
        relay.tick()
        assertThat(outbox.isPublished(second.id)).isTrue()
        assertThat(bus.publishedIds()).containsExactly(first.id, second.id)
    }

    @Test
    @DisplayName("1 分より新しい行は拾わない。コミットの直後の送信に任せる")
    fun leavesFreshRowsToPublisher() {
        val fresh = outbox.add(occurredAt = clock.instant().minus(Duration.ofSeconds(30)))
        activity.record()

        relay.tick()

        assertThat(bus.published).isEmpty()
        assertThat(outbox.isPublished(fresh.id)).isFalse()
    }

    @Test
    @DisplayName("送ってから 7 日を過ぎた行を消す。送れていない行は消さない")
    fun deletesOldPublishedRows() {
        val old = clock.instant().minus(Duration.ofDays(8))
        val expired = outbox.add(occurredAt = old, publishedAt = old)
        val recent = outbox.add(occurredAt = old, publishedAt = clock.instant().minus(Duration.ofDays(6)))
        val stuck = outbox.add(occurredAt = old)
        bus.down = true
        activity.record()

        relay.tick()

        assertThat(outbox.exists(expired.id)).isFalse()
        assertThat(outbox.exists(recent.id)).isTrue()
        assertThat(outbox.exists(stuck.id)).isTrue()
    }
}
