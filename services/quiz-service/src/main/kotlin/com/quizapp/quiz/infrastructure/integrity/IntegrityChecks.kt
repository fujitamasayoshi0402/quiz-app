package com.quizapp.quiz.infrastructure.integrity

import java.util.UUID

/**
 * DB の制約では表しきれない決まり（DEV-115）。行をまたぐもの、文章の中身を読むもの。
 *
 * ドメインが保存のたびに守っているが、シード、手で流した SQL、マイグレーションはドメインを通らない。
 * 崩れたときに気づけるよう、決まりごとに名前を付けてログに出す。**名前を変えると、ログの絞り込みが変わる**
 */
enum class IntegrityRule(val code: String, val description: String) {
    /** 公開中は 2〜6 個、下書きでも 6 個まで（`Quiz`、DEV-243） */
    CHOICE_COUNT("choice-count", "選択肢の数が決まりと違う"),

    /** 公開中は 1 個以上 */
    CORRECT_CHOICE_COUNT("correct-choice-count", "公開中なのに正解がない"),

    EXPLANATION_BLANK("explanation-blank", "公開中なのに解説が空"),

    /** 図は消さないため、ドメインを通っていれば起きない（ADR-0020） */
    MISSING_FIGURE("missing-figure", "解説が、テナントにない図を指している"),

    /** カテゴリを消すと、配下の難易度とクイズも一緒に消える（ADR-0007） */
    ALIVE_UNDER_DELETED_PARENT("alive-under-deleted-parent", "削除済みのカテゴリか難易度の下に、削除されていないものがある"),
}

/** 崩れていたもの。**ID だけを持つ。** 問題文や解説はログに出さない */
data class IntegrityViolation(val rule: IntegrityRule, val tenantId: UUID, val subjectId: UUID)

/**
 * 決まりを DB に当てる。テナントを 1 つずつ、そのテナントを設定して読む。
 *
 * テナントをまたいで読む印（Outbox の拾い直しの `app.outbox_relay`）は作らない。
 * 行レベルセキュリティの外に出る口を増やさずに済み、別のテナントの図を「ある」と数えることもない
 */
interface IntegrityChecks {
    fun tenantIds(): List<UUID>

    fun check(tenantId: UUID): List<IntegrityViolation>
}
