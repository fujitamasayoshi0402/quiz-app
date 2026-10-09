package com.quizapp.notification.usecase

import com.quizapp.notification.domain.SlackWebhookSetting
import com.quizapp.notification.domain.SlackWebhookSettings
import com.quizapp.notification.domain.SlackWebhookStore
import com.quizapp.notification.domain.SlackWebhookUrl
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service

/**
 * テナントの Slack の通知先を設定する（ADR-0022）。管理者だけが触れる。
 *
 * URL は SSM に、設定したという印は DB に置く。**2 か所に書くため、SSM への書き込みを DB のトランザクションの中で行う。**
 * SSM が失敗すれば、DB もロールバックする。ずれるのは、SSM に書けたあとでコミットだけが失敗したときに限られる。
 */
@Service
class SlackWebhookUseCase(
    private val settings: SlackWebhookSettings,
    private val store: SlackWebhookStore,
    private val tenantTransaction: TenantTransaction,
) {
    fun find(): SlackWebhookSetting? = tenantTransaction.executeNullable { settings.find(TenantContext.require()) }

    /**
     * 設定する。すでにあれば置き換える。
     *
     * コミットだけが失敗すると、URL は置かれたのに、画面には「設定していない」と出る。設定し直せば揃う
     */
    fun configure(url: SlackWebhookUrl): SlackWebhookSetting = tenantTransaction.execute {
        val tenantId = TenantContext.require()
        settings.save(tenantId).also { store.put(tenantId, url) }
    }

    /**
     * 消す。設定していなくても成功にする。
     *
     * コミットだけが失敗すると、URL は消えたのに、画面には「設定済み」と出る。通知は届かない側に倒れる。もう一度消せば揃う
     */
    fun remove() = tenantTransaction.executeWithoutResult {
        val tenantId = TenantContext.require()
        settings.delete(tenantId)
        store.delete(tenantId)
    }
}
