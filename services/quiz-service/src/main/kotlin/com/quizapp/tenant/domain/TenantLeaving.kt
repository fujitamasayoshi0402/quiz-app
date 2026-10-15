package com.quizapp.tenant.domain

import java.util.UUID

/** テナントから退会できない理由（ADR-0030） */
enum class TenantLeaveBlock {
    /** 最後の管理者。抜けると、管理者のいないテナントが残る */
    LAST_ADMIN,

    /** パスワードを公開している共有のアカウント（デモ）。抜けると、見に来たほかの人も入れなくなる */
    SHARED_ACCOUNT,
    ;

    val value: String get() = name.lowercase()
}

/**
 * テナントの所属を外す。`core.tenant_members` と `core.users` は行レベルセキュリティの対象外（ADR-0006）
 */
interface TenantLeavings {
    /**
     * 退会できない理由。退会できるなら null。所属していることは呼ぶ側が確かめてある（パスの規約）。
     *
     * テナントの行を `FOR UPDATE` で押さえてから数える。管理者が 2 人で同時に抜けて、管理者が 0 人にならないようにする
     */
    fun blockFor(tenantId: UUID, userId: UUID): TenantLeaveBlock?

    /** 所属を外す。行は消さずに論理削除する。もう一度入るときは、新しい所属として入る */
    fun leave(tenantId: UUID, userId: UUID)
}

/**
 * 退会する人の、そのテナントでの回答の履歴を消す（ADR-0030）。answer のモジュールが実装する。
 *
 * 呼ぶ側（テナント）が持つインターフェース。テナントのモジュールは answer の表に触れない（ADR-0023 と同じ分け方）。
 * テナントは行レベルセキュリティが絞る
 */
interface MemberAnswerHistory {
    /** 挑戦（回答と出題を含む）と、ランキングへの参加を消す */
    fun eraseOf(userId: UUID)
}

class TenantLeaveNotAllowedException(val reason: TenantLeaveBlock) : RuntimeException("退会できません: $reason")
