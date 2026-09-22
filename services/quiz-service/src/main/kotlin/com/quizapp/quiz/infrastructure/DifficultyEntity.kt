package com.quizapp.quiz.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(schema = "quiz", name = "difficulties")
data class DifficultyEntity(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val categoryId: UUID,
    val name: String,
    val level: Int,
    val sortOrder: Int = 0,
    val description: String? = null,
    @ReadOnlyProperty val createdAt: Instant? = null,
    @ReadOnlyProperty val updatedAt: Instant? = null,
    @ReadOnlyProperty val deletedAt: Instant? = null,
)
