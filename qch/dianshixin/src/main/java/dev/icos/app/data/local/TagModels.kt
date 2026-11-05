package dev.icos.app.data.local

import java.util.UUID

data class FinanceTagGroup(
    val id: UUID,
    val name: String,
    val sortOrder: Long,
    val isDefault: Boolean,
    val localRevision: Long,
)

data class FinanceTag(
    val id: UUID,
    val groupId: UUID,
    val name: String,
    val color: String?,
    val sortOrder: Long,
    val archivedAt: Long?,
    val localRevision: Long,
)

internal fun tagNameIsValid(value: String): Boolean =
    value.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= 100 }

internal fun tagColorIsValid(value: String?): Boolean = value == null || value.toByteArray(Charsets.UTF_8).size <= 32
