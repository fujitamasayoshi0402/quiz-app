package com.quizapp.support.fake

import com.quizapp.quiz.infrastructure.integrity.IntegrityChecks
import com.quizapp.quiz.infrastructure.integrity.IntegrityViolation
import java.util.UUID

/** 決まりを当てる代わりに、用意した崩れたものを返す。テナントは 1 つ */
class FakeIntegrityChecks : IntegrityChecks {
    val violations = mutableListOf<IntegrityViolation>()

    /** 立てると、DB に接続できないものとして失敗する */
    var down = false

    /** 流した回数（テナントの一覧を引いた回数） */
    var runs = 0
        private set

    private val tenant = UUID.randomUUID()

    override fun tenantIds(): List<UUID> {
        runs++
        check(!down) { "DB に接続できません" }
        return listOf(tenant)
    }

    override fun check(tenantId: UUID): List<IntegrityViolation> = violations.toList()
}
