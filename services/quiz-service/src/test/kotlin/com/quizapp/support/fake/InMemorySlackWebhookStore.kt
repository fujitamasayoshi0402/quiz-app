package com.quizapp.support.fake

import com.quizapp.notification.domain.SlackWebhookStore
import com.quizapp.notification.domain.SlackWebhookStoreUnavailableException
import com.quizapp.notification.domain.SlackWebhookUrl
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** SSM の代わり。置いた URL をテナントごとに持ち、テストから中身を確かめられる */
class InMemorySlackWebhookStore : SlackWebhookStore {

    private val urls = ConcurrentHashMap<UUID, String>()

    /** 立てると、置くのも消すのも失敗する。SSM に届かなかったときの振る舞いを確かめる */
    @Volatile var failing = false

    override fun put(tenantId: UUID, url: SlackWebhookUrl) {
        if (failing) throw SlackWebhookStoreUnavailableException()
        urls[tenantId] = url.value
    }

    override fun delete(tenantId: UUID) {
        if (failing) throw SlackWebhookStoreUnavailableException()
        urls.remove(tenantId)
    }

    fun find(tenantId: UUID): String? = urls[tenantId]
}
