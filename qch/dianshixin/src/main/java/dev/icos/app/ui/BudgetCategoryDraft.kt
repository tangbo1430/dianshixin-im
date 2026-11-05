package dev.icos.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceCategory
import java.util.UUID

internal class BudgetCategoryDraft(
    val expected: FinanceBudgetOverview,
    val scope: dev.icos.app.data.local.BudgetScope,
    categories: List<FinanceCategory>,
) {
    val categories = categories
    var saveQueued by mutableStateOf(false)
    val amounts = mutableStateMapOf<UUID, String>().apply {
        val initial = if (scope == dev.icos.app.data.local.BudgetScope.DEFAULT) expected.defaultCategoryAmounts else expected.overrideCategoryAmounts
        initial.forEach { (id, amount) -> put(id, moneyText(amount)) }
    }
}
