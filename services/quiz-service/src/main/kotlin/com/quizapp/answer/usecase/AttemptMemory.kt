package com.quizapp.answer.usecase

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.quizapp.answer.domain.Attempt
import com.quizapp.cache.ReadCacheProperties
import com.quizapp.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 利用者ごとの中断中の挑戦と、裏で続けている挑戦の保存（ADR-0038 の 2、DEV-240）。
 *
 * 「始める」を、止まっている DB の復帰（15〜28 秒）を待たずに返すために持つ。
 * 挑戦の開始・回答・完了・破棄はどれもこのタスクの API を通るため、メモリが正しい。
 * まだ持っていない利用者（起動の直後など）は、今どおり DB で確かめる。
 *
 * - **テナントで分けて持つ。** 鍵に必ずテナントを含める（ADR-0038）
 * - **保存は利用者ごとに順に行う。** 前の挑戦の保存と、それを破棄して始めた次の挑戦の保存が入れ替わらないようにする
 * - 保存している挑戦への次の操作（回答・再開・完了・破棄）は、[awaitSave] で保存が終わるまで待つ
 *
 * メモリに持たない設定（テスト）では [enabled] が false になり、挑戦は今どおり DB で始める。
 */
@Component
class AttemptMemory internal constructor(
    val enabled: Boolean,
    private val executor: Executor,
    expireAfterAccess: Duration,
    private val saveTimeout: Duration,
) : DisposableBean {

    @Autowired
    constructor(properties: ReadCacheProperties) : this(
        enabled = properties.enabled,
        executor = savingExecutor(),
        expireAfterAccess = properties.expireAfterAccess,
        saveTimeout = SAVE_TIMEOUT,
    )

    /** 利用者が持つ中断中の挑戦。[attempt] が null なら、ないと分かっている。[version] は書き換えるたびに増える */
    data class Known(val attempt: Attempt?, val answeredCount: Int, val version: Long)

    private data class Key(val tenantId: UUID, val userId: UUID)

    /** 鍵がなければ、まだ分からない（DB で確かめる） */
    private val known: Cache<Key, Known> = Caffeine.newBuilder()
        .expireAfterAccess(expireAfterAccess)
        .maximumSize(MAXIMUM_USERS)
        .build()

    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    /** 挑戦の ID → その保存。終わったら消す */
    private val saves = ConcurrentHashMap<UUID, CompletableFuture<Void>>()

    /** 利用者ごとの、最後に頼んだ保存。次の保存はこれのあとに続ける */
    private val lastSaves = ConcurrentHashMap<Key, CompletableFuture<Void>>()

    private var versions = 0L

    /** 同じ利用者の「始める」「完了」「破棄」を、1 つずつ行う。同時に始めて 2 つできないようにする */
    fun <T> locked(userId: UUID, block: () -> T): T =
        locks[Math.floorMod(key(userId).hashCode(), LOCK_STRIPES)].withLock(block)

    fun known(userId: UUID): Known? = if (enabled) known.getIfPresent(key(userId)) else null

    /** 中断中の挑戦が [attempt] になった（null ならなくなった）。[locked] の中で呼ぶ */
    fun remember(userId: UUID, attempt: Attempt?, answeredCount: Int = 0) {
        if (!enabled) return
        known.put(key(userId), Known(attempt, answeredCount, nextVersion()))
    }

    /**
     * DB から読んだ中断中の挑戦を持つ。**読み始めてから書き換わっていたら持たない。**
     * 読んでいる間（DB の復帰を待つ間）に始めた挑戦を、古い読み取りで上書きしないため。[readStarted] は読む前の [known]
     */
    fun rememberRead(userId: UUID, readStarted: Known?, attempt: Attempt?, answeredCount: Int) {
        if (!enabled) return
        known.asMap().compute(key(userId)) { _, current ->
            if (current?.version == readStarted?.version) Known(attempt, answeredCount, nextVersion()) else current
        }
    }

    /** [attemptId] の回答の数が変わった。別の挑戦を持っていれば何もしない */
    fun answered(userId: UUID, attemptId: UUID, answeredCount: Int) = update(userId, attemptId) {
        it.copy(answeredCount = answeredCount)
    }

    /** [attemptId] が終わった。別の挑戦を持っていれば何もしない */
    fun finished(userId: UUID, attemptId: UUID) = update(userId, attemptId) { Known(null, 0, it.version) }

    private fun update(userId: UUID, attemptId: UUID, change: (Known) -> Known) {
        if (!enabled) return
        known.asMap().computeIfPresent(key(userId)) { _, current ->
            if (current.attempt?.id == attemptId) change(current).copy(version = nextVersion()) else current
        }
    }

    /** 分からなくなった。次は DB で確かめる */
    fun forget(userId: UUID) {
        known.invalidate(key(userId))
    }

    /**
     * 挑戦 [attemptId] の保存を、この利用者の前の保存のあとに裏で行う。**テナントは、頼んだ要求のものを使う。**
     *
     * 失敗したら（DB の復帰が間に合わない、など）、この利用者の中断中の挑戦は分からなくなったものとし、次は DB で確かめる。
     * 保存できなかった挑戦への回答は、DB に挑戦がないため「見つからない」になる
     */
    fun save(userId: UUID, attemptId: UUID, task: () -> Unit) {
        val key = key(userId)
        val previous = lastSaves[key] ?: CompletableFuture.completedFuture(null)
        val future = previous.handle { _, _ -> }.thenRunAsync(
            { TenantContext.within(key.tenantId) { task() } },
            executor,
        )
        saves[attemptId] = future
        lastSaves[key] = future
        future.whenComplete { _, error ->
            saves.remove(attemptId, future)
            lastSaves.remove(key, future)
            if (error != null) {
                log.error("挑戦を保存できませんでした: attempt={}", attemptId, error)
                known.invalidate(key)
            }
        }
    }

    /** [attemptId] を保存している間は待つ。保存できなかったときは、そのまま進めて DB に任せる */
    fun awaitSave(attemptId: UUID) = await(saves[attemptId])

    /** この利用者の保存がすべて終わるまで待つ。DB で中断中の挑戦を確かめる前に呼ぶ */
    fun awaitSaves(userId: UUID) = await(lastSaves[key(userId)])

    private fun await(future: CompletableFuture<Void>?) {
        if (future == null) return
        try {
            future.get(saveTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: ExecutionException) {
            // 保存できなかった。DB に挑戦がないため、呼び出し側の読み込みで「見つからない」になる
        } catch (e: TimeoutException) {
            throw AttemptStillSavingException(e)
        }
    }

    @Synchronized
    private fun nextVersion(): Long = ++versions

    private fun key(userId: UUID) = Key(TenantContext.require(), userId)

    override fun destroy() {
        (executor as? ThreadPoolTaskExecutor)?.shutdown()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AttemptMemory::class.java)

        /**
         * 保存を待つ長さ。API Gateway の待ち時間（30 秒）より短くし、間に合わなければ 503 で再試行を促す。
         * 最初の問題を読んでいる間に、たいてい DB の復帰は終わる
         */
        private val SAVE_TIMEOUT: Duration = Duration.ofSeconds(25)
        private const val MAXIMUM_USERS = 10_000L
        private const val LOCK_STRIPES = 64
        private const val SHUTDOWN_WAIT_SECONDS = 60

        /** タスクを止めるとき（デプロイ）は、頼まれた保存を終えてから止まる */
        private fun savingExecutor() = ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            setThreadNamePrefix("attempt-save-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS)
            initialize()
        }

        /** 単体テスト用。メモリに持たない */
        fun disabled() = AttemptMemory(false, Runnable::run, Duration.ofMinutes(1), Duration.ofSeconds(1))
    }
}

/** 挑戦の保存（DB の復帰）が、待てる長さのうちに終わらなかった。少し待って同じ操作をやり直せばよい */
class AttemptStillSavingException(cause: Throwable? = null) :
    RuntimeException("クイズの準備をしています。少し待ってから、もう一度お試しください", cause)
