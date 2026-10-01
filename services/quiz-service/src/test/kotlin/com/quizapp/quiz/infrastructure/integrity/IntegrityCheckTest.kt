package com.quizapp.quiz.infrastructure.integrity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.quizapp.logging.LogContext
import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import com.quizapp.support.MutableClock
import com.quizapp.support.fake.FakeIntegrityChecks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * データの整合性を確かめる時機（DEV-115）。**利用者が DB を使っている間に、1 日 1 回だけ流す**ことが要点。
 * 決まった時刻に流すと、一時停止中の Aurora を起こす。
 *
 * 決まりそのもの（SQL）は `IntegrityChecksApiTest` が見る。
 */
class IntegrityCheckTest {

    private val clock = MutableClock()
    private val activity = DatabaseActivity(clock)
    private val checks = FakeIntegrityChecks()
    private val check = IntegrityCheck(checks, activity, IntegrityProperties(), clock)

    @Test
    @DisplayName("起動してから誰も使っていなければ、DB に触れない")
    fun idleAfterStartup() {
        check.tick()

        assertThat(checks.runs).isZero()
    }

    @Test
    @DisplayName("使われたら流し、1 日たつまでは流さない")
    fun runsOncePerDay() {
        activity.record()
        check.tick()
        assertThat(checks.runs).isEqualTo(1)

        clock.advance(Duration.ofHours(23))
        activity.record()
        check.tick()
        assertThat(checks.runs).isEqualTo(1)

        clock.advance(Duration.ofHours(1))
        activity.record()
        check.tick()
        assertThat(checks.runs).isEqualTo(2)
    }

    @Test
    @DisplayName("1 日たっていても、利用者が DB を使ってから 5 分を過ぎていれば流さない")
    fun waitsForUse() {
        activity.record()
        check.tick()
        clock.advance(Duration.ofDays(2))

        check.tick()
        assertThat(checks.runs).isEqualTo(1)

        activity.record()
        check.tick()
        assertThat(checks.runs).isEqualTo(2)
    }

    @Test
    @DisplayName("確かめられなかったら、次の回にやり直す")
    fun retriesFailure() {
        checks.down = true
        activity.record()
        check.tick()
        assertThat(checks.runs).isEqualTo(1)

        checks.down = false
        clock.advance(Duration.ofMinutes(1))
        check.tick()
        assertThat(checks.runs).isEqualTo(2)
    }

    @Test
    @DisplayName("崩れたものを 1 件ずつ WARN で出し、件数をアラームが数える項目として出す")
    fun logsViolations() {
        val tenant = UUID.randomUUID()
        val quiz = UUID.randomUUID()
        checks.violations += IntegrityViolation(IntegrityRule.MISSING_FIGURE, tenant, quiz)
        checks.violations += IntegrityViolation(IntegrityRule.EXPLANATION_BLANK, tenant, quiz)
        activity.record()

        val logs = capture { check.tick() }

        val warnings = logs.filter { it.level == Level.WARN && it.has(IntegrityCheck.RULE) }
        assertThat(
            warnings.map {
                it.value(IntegrityCheck.RULE)
            },
        ).containsExactly("missing-figure", "explanation-blank")
        assertThat(warnings.map { it.mdcPropertyMap[LogContext.TENANT_ID] }).containsOnly(tenant.toString())
        assertThat(warnings.map { it.value(IntegrityCheck.SUBJECT_ID) }).containsOnly(quiz.toString())

        val summary = logs.single { it.has(IntegrityCheck.VIOLATIONS) }
        assertThat(summary.value(IntegrityCheck.VIOLATIONS)).isEqualTo(2)
        assertThat(summary.level).isEqualTo(Level.WARN)
    }

    @Test
    @DisplayName("崩れていなくても、0 件として出す。流れたことがログで分かる")
    fun logsZero() {
        activity.record()

        val logs = capture { check.tick() }

        val summary = logs.single { it.has(IntegrityCheck.VIOLATIONS) }
        assertThat(summary.value(IntegrityCheck.VIOLATIONS)).isEqualTo(0)
        assertThat(summary.level).isEqualTo(Level.INFO)
    }

    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        // MDC（テナント）は出したときのものを残す。あとから読むと、消したあとの空のものになる
        val logs = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        val logger = LoggerFactory.getLogger(IntegrityCheck::class.java) as Logger
        logger.addAppender(logs)
        try {
            block()
        } finally {
            logger.detachAppender(logs)
        }
        return logs.list
    }

    private fun ILoggingEvent.has(key: String) = keyValuePairs.orEmpty().any { it.key == key }

    private fun ILoggingEvent.value(key: String) = keyValuePairs.orEmpty().single { it.key == key }.value
}
