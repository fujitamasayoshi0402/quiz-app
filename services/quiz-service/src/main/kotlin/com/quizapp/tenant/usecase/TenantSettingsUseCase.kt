package com.quizapp.tenant.usecase

import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import com.quizapp.tenant.domain.TenantCannotBePublicException
import com.quizapp.tenant.domain.TenantSettings
import com.quizapp.tenant.domain.TenantSettingsStore
import com.quizapp.tenant.domain.TenantVisibility
import org.springframework.stereotype.Service

/** テナントの設定を読み書きする。管理者だけが触れる（パスの規約。`/api/t/{slug}/admin/...`） */
@Service
class TenantSettingsUseCase(private val store: TenantSettingsStore, private val tenantTransaction: TenantTransaction) {

    fun find(): TenantSettings = tenantTransaction.execute { store.find(TenantContext.require()) }

    /** 置き換える。同じ値でも成功にする。利用者が作ったテナントは、公開にできない（ADR-0028） */
    fun update(settings: TenantSettings): TenantSettings = tenantTransaction.execute {
        val tenantId = TenantContext.require()
        if (settings.visibility == TenantVisibility.PUBLIC && !store.find(tenantId).canBePublic) {
            throw TenantCannotBePublicException()
        }
        store.save(tenantId, settings)
        store.find(tenantId)
    }
}
