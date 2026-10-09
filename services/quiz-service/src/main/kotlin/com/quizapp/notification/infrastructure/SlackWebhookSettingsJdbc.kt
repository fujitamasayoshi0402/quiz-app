package com.quizapp.notification.infrastructure

import com.quizapp.notification.domain.SlackWebhookSetting
import com.quizapp.notification.domain.SlackWebhookSettings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SlackWebhookSettingsJdbc(private val jdbcTemplate: JdbcTemplate) : SlackWebhookSettings {

    override fun find(tenantId: UUID): SlackWebhookSetting? = jdbcTemplate.query(
        "SELECT configured_at FROM quiz.slack_webhooks WHERE tenant_id = ?",
        mapper,
        tenantId,
    ).firstOrNull()

    override fun save(tenantId: UUID): SlackWebhookSetting = jdbcTemplate.query(
        """
        INSERT INTO quiz.slack_webhooks (tenant_id) VALUES (?)
        ON CONFLICT (tenant_id) DO UPDATE SET configured_at = now()
        RETURNING configured_at
        """,
        mapper,
        tenantId,
    ).single()

    override fun delete(tenantId: UUID) {
        jdbcTemplate.update("DELETE FROM quiz.slack_webhooks WHERE tenant_id = ?", tenantId)
    }

    private val mapper = RowMapper { rs, _ -> SlackWebhookSetting(rs.getTimestamp("configured_at").toInstant()) }
}
