package dev.icos.app.ui

import dev.icos.app.data.local.FinanceCategory
import java.util.UUID

internal fun categoryExcludesBudget(categoryId: UUID?, categories: List<FinanceCategory>): Boolean {
    val category = categories.firstOrNull { it.id == categoryId } ?: return false
    return category.excludeBudget || categories.any {
        it.id == category.parentId && it.ledgerId == category.ledgerId && it.excludeBudget
    }
}
