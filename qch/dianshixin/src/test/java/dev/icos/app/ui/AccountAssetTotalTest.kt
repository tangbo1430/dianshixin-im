package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AccountAssetTotalTest {
    @Test
    fun assetTotalTracksInclusionAndKeepsCurrenciesSeparate() {
        val first =
            FinanceAccount(
                UUID.randomUUID(),
                "现金",
                "CNY",
                Money(100025, 2, "CNY"),
                1,
                null,
                true,
                true,
            )
        val second =
            FinanceAccount(
                UUID.randomUUID(),
                "备用金",
                "CNY",
                Money(5050, 2, "CNY"),
                1,
                null,
                false,
                true,
            )
        val foreignCurrency =
            FinanceAccount(
                UUID.randomUUID(),
                "外币",
                "USD",
                Money(900, 2, "USD"),
                1,
                null,
                true,
                true,
            )

        assertEquals(Money(100025, 2, "CNY"), accountAssetTotal(listOf(first, second, foreignCurrency), "CNY"))
        assertEquals(Money(105075, 2, "CNY"), accountAssetTotal(listOf(first, second.copy(includeInAssets = true), foreignCurrency), "CNY"))
        assertEquals(Money(9, 0, "USD"), accountAssetTotal(listOf(first, second, foreignCurrency), "USD"))
    }

    @Test
    fun positionSeparatesAssetsLiabilitiesAndNet() {
        val cash = FinanceAccount(UUID.randomUUID(), "现金", "CNY", Money(7800, 2, "CNY"), 1, null, true, true)
        val debt = FinanceAccount(UUID.randomUUID(), "贷款", "CNY", Money(8000, 2, "CNY"), 1, null, true, true, type = "LIABILITY")
        val result = accountPosition(listOf(cash, debt), "CNY")!!
        assertTrue(Money(7800, 2, "CNY").sameValue(result.assets))
        assertTrue(Money(8000, 2, "CNY").sameValue(result.liabilities))
        assertTrue(Money(-200, 2, "CNY").sameValue(result.net))
        assertTrue(Money(7800, 2, "CNY").sameValue(accountAssetTotal(listOf(cash, debt), "CNY")!!))
        assertTrue(Money(-200, 2, "CNY").sameValue(accountPosition(listOf(cash, debt.copy(includeInAssets = false)), "CNY")!!.net))
    }

    @Test
    fun globalPositionIncludesCashBankAndHiddenArchivedDebt() {
        val bank = FinanceAccount(UUID.randomUUID(), "银行卡", "CNY", Money(500, 0, "CNY"), 1, null, true, true, type = "BANK")
        val cash = bank.copy(id = UUID.randomUUID(), type = "CASH", balance = Money(25, 0, "CNY"), visibilityMode = "SELECTED")
        val debt = bank.copy(id = UUID.randomUUID(), type = "LIABILITY", balance = Money(30, 0, "CNY"),
            includeInAssets = false, archivedAt = 1L, selectable = false, visibilityMode = "SELECTED")
        val position = accountPosition(listOf(bank, cash, debt), "CNY")!!
        assertTrue(Money(525, 0, "CNY").sameValue(position.assets))
        assertTrue(Money(30, 0, "CNY").sameValue(position.liabilities))
        assertTrue(Money(495, 0, "CNY").sameValue(position.net))
        assertTrue(position.assets.sameValue(accountAssetTotal(listOf(bank, cash, debt), "CNY")!!))
    }

    @Test
    fun borrowingAndRepaymentKeepGrossAssetsSeparateFromSignedDebt() {
        val borrowedCash = FinanceAccount(UUID.randomUUID(), "现金", "CNY", Money(100, 0, "CNY"), 1, null, true, true)
        val borrowedDebt = FinanceAccount(UUID.randomUUID(), "贷款", "CNY", Money(100, 0, "CNY"), 1, null, true, true, type = "LIABILITY")
        val borrowed = accountPosition(listOf(borrowedCash, borrowedDebt), "CNY")!!
        assertTrue(Money(100, 0, "CNY").sameValue(borrowed.assets))
        assertTrue(Money(100, 0, "CNY").sameValue(borrowed.liabilities))
        assertTrue(Money(0, 0, "CNY").sameValue(borrowed.net))

        val repaidCash = borrowedCash.copy(balance = Money(78, 0, "CNY"))
        val remainingDebt = borrowedDebt.copy(balance = Money(80, 0, "CNY"))
        val repaid = accountPosition(listOf(repaidCash, remainingDebt), "CNY")!!
        assertTrue(Money(78, 0, "CNY").sameValue(repaid.assets))
        assertTrue(Money(80, 0, "CNY").sameValue(repaid.liabilities))
        assertTrue(Money(-2, 0, "CNY").sameValue(repaid.net))
    }
}
