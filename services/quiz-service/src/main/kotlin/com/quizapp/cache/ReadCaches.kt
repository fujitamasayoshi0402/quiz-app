package com.quizapp.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.Optional

/**
 * 出題の読み取りを、このタスクのメモリに持つ（ADR-0038）。
 *
 * prod の quiz-service はタスク 1 つで常に動き、Aurora が一時停止している間もメモリは消えない。
 * 読むものをメモリに持てば、止まっている DB の復帰（15〜28 秒）を待たずに答えられる。
 *
 * **変更はすべてこのタスクの API を通る前提で、変更を受けたその場で消す。** タスクを 2 つ以上にするなら、ADR-0038 を見直す
 */
@ConfigurationProperties("app.read-cache")
data class ReadCacheProperties(
    /** 止めると、毎回 DB を読む。テストは止める。テストは DB を直接書き換えるため、メモリが古くなる */
    val enabled: Boolean = true,
    /**
     * 変わりうるもの（所属、カテゴリの一覧）を、この時間ごとに裏で読み直す。読み直している間は、前の値を返す。
     * 変更を受けて消し損ねたとき（DB を直接書き換えたときなど）も、この時間で追いつく
     */
    val refreshAfter: Duration = Duration.ofMinutes(DEFAULT_REFRESH_MINUTES),
    /** 使われないまま、この時間が過ぎたら捨てる。見に来る間隔（数時間〜数日）より長くする */
    val expireAfterAccess: Duration = Duration.ofDays(DEFAULT_EXPIRE_DAYS),
    /** キャッシュ 1 つあたりの件数の上限 */
    val maximumSize: Long = DEFAULT_MAXIMUM_SIZE,
) {
    private companion object {
        const val DEFAULT_REFRESH_MINUTES = 5L
        const val DEFAULT_EXPIRE_DAYS = 7L
        const val DEFAULT_MAXIMUM_SIZE = 10_000L
    }
}

@Component
@EnableConfigurationProperties(ReadCacheProperties::class)
class ReadCaches(private val properties: ReadCacheProperties) {

    /**
     * 一度決まったら変わらない対応（認証基盤の ID → 利用者の ID、slug → テナントの ID）。読み直さない。
     * 見つからなかったことも持つ。作ったときは [ReadCache.forget] で消す
     */
    fun <K : Any, V : Any> stable(load: (K) -> V?): ReadCache<K, V> = build(refresh = false, load)

    /** 変わりうるもの。[ReadCacheProperties.refreshAfter] ごとに裏で読み直す */
    fun <K : Any, V : Any> refreshing(load: (K) -> V?): ReadCache<K, V> = build(refresh = true, load)

    private fun <K : Any, V : Any> build(refresh: Boolean, load: (K) -> V?): ReadCache<K, V> {
        if (!properties.enabled) return ReadCache(null, load)
        val builder = Caffeine.newBuilder()
            .maximumSize(properties.maximumSize)
            .expireAfterAccess(properties.expireAfterAccess)
        if (refresh) builder.refreshAfterWrite(properties.refreshAfter)
        return ReadCache(builder.build { key: K -> Optional.ofNullable(load(key)) }, load)
    }
}

/** 見つからなかった（null）ことも持つ。Caffeine は null を持てないため、[Optional] に包む */
class ReadCache<K : Any, V : Any> internal constructor(
    private val cache: LoadingCache<K, Optional<V>>?,
    private val load: (K) -> V?,
) {
    fun get(key: K): V? = if (cache == null) load(key) else cache.get(key).orElse(null)

    /**
     * 消す。**トランザクションの中なら、終わったときにもう一度消す。**
     *
     * 確定する前に別の要求が読み直すと、変更の前の値を持ってしまう。同じトランザクションの中で読み直すと、
     * 確定していない値を持ち、取り消されたときに残る。終わったときに消せば、どちらも次に読むときに直る
     */
    fun forget(key: K) = forgetIf { it == key }

    fun forgetIf(predicate: (K) -> Boolean) {
        val map = cache?.asMap() ?: return
        map.keys.removeIf(predicate)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        map.keys.removeIf(predicate)
                    }
                },
            )
        }
    }
}
