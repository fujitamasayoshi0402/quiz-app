package com.quizapp.quiz.infrastructure.integrity

import com.quizapp.logging.LogContext
import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * データの整合性を、1 日に 1 回確かめる（DEV-115）。定期的に呼ばれる（[IntegrityConfiguration]）。
 *
 * **利用者の要求で DB を使ってから [IntegrityProperties.activeWindow] の間にだけ流す。** Outbox の拾い直しと同じ考え方。
 * 決まった時刻に流すと、そのたびに一時停止中の Aurora を起こす（min 0 ACU）。誰も使わない日は流れないが、
 * そういう日はデータも変わらない。
 *
 * 前に流した時刻はタスクごとに持つ。タスクは毎朝起動し直す（夜間の停止）ため、その日の最初の利用で流れる
 */
class IntegrityCheck(
    private val checks: IntegrityChecks,
    private val activity: DatabaseActivity,
    private val properties: IntegrityProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var lastRunAt: Instant? = null

    // DB の失敗は、ここで記録して終える。前に流した時刻を進めず、次の回にやり直す
    @Suppress("TooGenericExceptionCaught")
    fun tick() {
        val now = clock.instant()
        if (!due(now)) return

        val tenants: List<UUID>
        val violations = try {
            tenants = checks.tenantIds()
            tenants.flatMap(checks::check)
        } catch (e: RuntimeException) {
            log.warn("データの整合性を確かめられませんでした。次の回にやり直します", e)
            return
        }
        lastRunAt = now

        violations.forEach(::report)
        // 件数を項目として出し、アラームが数える（modules/quiz-service の alarms.tf）。**項目の名前を変えると、アラームが鳴らなくなる**。
        // 崩れていなくても 0 として出す。流れたことがログで分かる
        val level = if (violations.isEmpty()) log.atInfo() else log.atWarn()
        level.addKeyValue(VIOLATIONS, violations.size)
            .log("データの整合性を確かめました: 崩れていたもの {} 件（{} テナント）", violations.size, tenants.size)
    }

    private fun due(now: Instant): Boolean = activity.usedWithin(properties.activeWindow) &&
        lastRunAt.let { it == null || !now.isBefore(it.plus(properties.every)) }

    private fun report(violation: IntegrityViolation) {
        LogContext.putTenant(violation.tenantId)
        try {
            log.atWarn()
                .addKeyValue(RULE, violation.rule.code)
                .addKeyValue(SUBJECT_ID, violation.subjectId.toString())
                .log("データの整合性が崩れています: {}（{}）", violation.rule.description, violation.subjectId)
        } finally {
            LogContext.clear()
        }
    }

    companion object {
        const val VIOLATIONS = "integrity.violations"
        const val RULE = "integrity.rule"
        const val SUBJECT_ID = "integrity.subject_id"
    }
}
