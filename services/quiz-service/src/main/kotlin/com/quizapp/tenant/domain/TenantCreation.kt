package com.quizapp.tenant.domain

import java.util.UUID

/**
 * テナントの数の上限（ADR-0028）。null なら上限なし。
 *
 * 利用者が作ったテナントにだけ、作るときに [SELF_SERVICE] を入れる。運用者が作ったテナント（デモなど）は上限なし
 */
data class TenantLimits(val quizzes: Int?, val figures: Int?) {
    companion object {
        /** クイズはゴミ箱のものも数える。図 50 個は、1 つ最大 20 MB で 1 テナント 1 GB ほど */
        val SELF_SERVICE = TenantLimits(quizzes = 100, figures = 50)
    }
}

/** テナントを作れない理由 */
enum class TenantCreationBlock {
    /** パスワードを公開している共有のアカウント（デモ） */
    SHARED_ACCOUNT,

    /** すでに 1 つ作っている。1 人 1 つ */
    ALREADY_CREATED,
    ;

    val value: String get() = name.lowercase()
}

/**
 * テナントを作る。`core.tenants` と `core.users` は行レベルセキュリティの対象外で、所属する前でも読み書きできる（ADR-0006）
 */
interface TenantCreations {
    /** 作れない理由。作れるなら null */
    fun blockFor(userId: UUID): TenantCreationBlock?

    /**
     * テナントを作り、ID を返す。作った人は [createdBy] として残る。所属は呼ぶ側が作る。
     *
     * slug が削除されていないテナントと重なれば [TenantSlugTakenException]、
     * [createdBy] がすでに作っていれば [TenantCreationNotAllowedException]（同時に来た要求も、DB の一意制約で止まる）
     */
    fun create(slug: String, name: String, createdBy: UUID, limits: TenantLimits): UUID
}

class TenantCreationNotAllowedException(val reason: TenantCreationBlock) : RuntimeException("テナントを作れません: $reason")

class TenantSlugTakenException(val slug: String) : RuntimeException("slug が使われています: $slug")
