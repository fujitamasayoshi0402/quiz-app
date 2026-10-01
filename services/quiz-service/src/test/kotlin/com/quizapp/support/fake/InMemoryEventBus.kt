package com.quizapp.support.fake

import com.quizapp.quiz.infrastructure.outbox.EventBus
import com.quizapp.quiz.infrastructure.outbox.OutboxEntry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 送ったイベントを覚えておくバス。EventBridge には送らない。
 *
 * [failing] に入れた ID は送れなかったことにする。[down] にすると、すべて送れない。
 * 送る側は別のスレッドで呼ぶため、スレッドをまたいで読める入れ物にしている
 */
class InMemoryEventBus : EventBus {
    val published: MutableList<OutboxEntry> = CopyOnWriteArrayList()
    val failing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile var down: Boolean = false

    override fun publish(entries: List<OutboxEntry>): Set<UUID> {
        val sent = entries.filterNot { down || it.id in failing }
        published += sent
        return sent.map { it.id }.toSet()
    }

    fun publishedIds(): List<UUID> = published.map { it.id }
}
