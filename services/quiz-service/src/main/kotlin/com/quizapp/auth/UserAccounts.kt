package com.quizapp.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 認証基盤の利用者（`external_id`）と、アプリの利用者（`core.users.id`）の対応。
 *
 * **アプリの利用者の ID は認証基盤から切り離す**（ADR-0016）。認証基盤を替えても、回答の履歴と所属が残る。
 * `core.users` は行レベルセキュリティの対象外のため、テナントを決める前でも読み書きできる。
 */
interface UserAccounts {

    fun findIdByExternalId(externalId: String): UUID?

    /**
     * 事前に登録した利用者（メールアドレスだけを持ち、まだ誰にも結び付いていない）に結び付ける。無ければ null。
     *
     * 最初の管理者や、スモークテストの利用者を、Cognito の `sub` を知らずに用意するために使う。
     */
    fun claimByEmail(externalId: String, email: String): UUID?

    /** そのメールアドレスを持つ利用者がいるか。結び付いているかどうかは問わない */
    fun existsByEmail(email: String): Boolean

    /** 利用者を作る。同じ [externalId] が同時に作られたときは、先に作られたほうを返す */
    fun create(externalId: String, email: String?, displayName: String): UUID
}

@Component
class UserAccountsJdbc(private val jdbcTemplate: JdbcTemplate) : UserAccounts {

    override fun findIdByExternalId(externalId: String): UUID? = jdbcTemplate.query(
        "SELECT id FROM core.users WHERE external_id = ?",
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        externalId,
    ).firstOrNull()

    override fun claimByEmail(externalId: String, email: String): UUID? = jdbcTemplate.query(
        """
        UPDATE core.users SET external_id = ?
        WHERE lower(email) = lower(?) AND external_id IS NULL
        RETURNING id
        """,
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        externalId,
        email,
    ).firstOrNull()

    override fun existsByEmail(email: String): Boolean = jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM core.users WHERE lower(email) = lower(?))",
        Boolean::class.java,
        email,
    ) == true

    override fun create(externalId: String, email: String?, displayName: String): UUID = jdbcTemplate.query(
        """
        INSERT INTO core.users (external_id, email, display_name) VALUES (?, ?, ?)
        ON CONFLICT (external_id) DO NOTHING
        RETURNING id
        """,
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        externalId,
        email,
        displayName,
    ).firstOrNull() ?: requireNotNull(findIdByExternalId(externalId))
}
