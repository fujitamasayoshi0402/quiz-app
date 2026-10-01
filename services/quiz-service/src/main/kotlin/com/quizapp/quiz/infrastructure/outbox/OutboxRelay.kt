package com.quizapp.quiz.infrastructure.outbox

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * 送れなかったイベントを拾い直す（ADR-0022）。定期的に呼ばれる（[EventsConfiguration]）。
 *
 * **利用者の要求で DB を使ってから [EventsProperties.Relay.activeWindow] の間だけ動く。**
 * いつも動かすと、定期的に DB へ接続することになり、Aurora が一時停止しなくなる（min 0 ACU）。
 * 代わりに、送れなかったものは次に誰かが使うまで遅れる。通知は急がないため、受け入れる
 *
 * コミットの直後の送信（[OutboxPublisher]）と重ならないよう、[EventsProperties.Relay.minAge] より新しい行は拾わない。
 */
class OutboxRelay(
    private val rows: OutboxRows,
    private val bus: EventBus,
    private val activity: DatabaseActivity,
    private val properties: EventsProperties.Relay,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // DB や EventBridge の失敗は、ここで記録して終える。何が起きても、次の回にやり直す
    @Suppress("TooGenericExceptionCaught")
    fun tick() {
        if (!activity.usedWithin(properties.activeWindow)) return

        val now = clock.instant()
        val result = try {
            rows.relay(
                olderThan = now.minus(properties.minAge),
                limit = properties.batchSize,
                publishedBefore = now.minus(properties.retention),
                send = bus::publish,
            )
        } catch (e: RuntimeException) {
            log.warn("拾い直しに失敗しました。次の回にやり直します", e)
            return
        }

        if (result.sent > 0 || result.failed > 0) {
            log.info("送れていなかったイベントを拾い直しました: 送った {} 件, 送れなかった {} 件", result.sent, result.failed)
        }
        if (result.deleted > 0) log.info("送ってから時間のたった Outbox の行を消しました: {} 件", result.deleted)
        // 監視（Phase 6）で閾値を決め、アラームにする。いまはログに出すだけ
        result.oldestUnpublished?.let {
            log.info("送れていない最も古いイベント: {} 分前", Duration.between(it, now).toMinutes())
        }
    }
}
