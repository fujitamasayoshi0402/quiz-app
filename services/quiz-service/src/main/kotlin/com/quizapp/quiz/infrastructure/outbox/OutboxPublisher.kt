package com.quizapp.quiz.infrastructure.outbox

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Outbox に書いたイベントを、**コミットの直後に**送る（ADR-0022）。
 *
 * - コミットの前には送らない。ロールバックしたのにイベントだけが届く、を起こさない
 * - **送るのは別のスレッド。** 操作の応答は、送れたかどうかを待たない。EventBridge が遅くても、クイズの操作は遅くならない
 * - 送れなかったもの（EventBridge の失敗、送る前にタスクが落ちた、待ち行列があふれた）は Outbox に残り、[OutboxRelay] が拾い直す
 */
class OutboxPublisher(private val bus: EventBus, private val rows: OutboxRows, private val executor: ExecutorService) :
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Outbox に書いたトランザクションの中で呼ぶ。コミットしたら送る */
    fun publishAfterCommit(entry: OutboxEntry) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Outbox に書くトランザクションの中で呼んでください。コミットを待たずに送ることになります"
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = dispatch(entry)
            },
        )
    }

    private fun dispatch(entry: OutboxEntry) {
        try {
            executor.execute { send(entry) }
        } catch (e: RejectedExecutionException) {
            log.warn("送る待ち行列があふれたか、止めている途中です。あとで拾い直します: {}, {}", entry.id, e.message)
        }
    }

    /** 送って、送れたら印を付ける。**例外を外に出さない**（別のスレッドで動き、受け取る者がいない） */
    @Suppress("TooGenericExceptionCaught")
    private fun send(entry: OutboxEntry) {
        try {
            if (entry.id in bus.publish(listOf(entry))) rows.markPublished(entry.tenantId, listOf(entry.id))
        } catch (e: RuntimeException) {
            // 送れたが印を付けられなかったときは、拾い直しがもう一度送る。受け手がイベントの ID で重複を捨てる
            log.warn("送ったイベントに印を付けられませんでした。あとで拾い直します: {}", entry.id, e)
        }
    }

    /** 止めるとき、待っているものを送り終えるまで少し待つ。間に合わなかったものは、次に起動したタスクが拾い直す */
    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("送り終えないまま止めます。残りはあとで拾い直します")
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
