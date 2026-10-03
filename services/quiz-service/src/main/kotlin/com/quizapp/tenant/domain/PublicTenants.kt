package com.quizapp.tenant.domain

import java.util.UUID

/** 公開テナントの一覧に出すもの。カテゴリやクイズは出さない。参加してから出題の画面で見る（ADR-0025） */
data class PublicTenant(val slug: String, val name: String, val joined: Boolean)

/** 参加するときに引く、公開テナント */
data class JoinableTenant(val id: UUID, val slug: String, val name: String)

/**
 * 公開テナントを引く。`core.tenants` と `core.tenant_members` は行レベルセキュリティの対象外で、
 * テナントを選ぶ前でも読める（ADR-0006）。**公開（`public`）で、削除されていないものだけを返す。**
 */
interface PublicTenants {
    /** 公開テナントの一覧。名前順。[userId] が所属しているかどうかも返す */
    fun listFor(userId: UUID): List<PublicTenant>

    /** 公開テナントなら返す。非公開・削除済み・存在しないものは、どれも null */
    fun findJoinable(slug: String): JoinableTenant?
}
