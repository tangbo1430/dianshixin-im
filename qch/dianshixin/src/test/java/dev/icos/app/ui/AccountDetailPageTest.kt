package dev.icos.app.ui

import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AccountDetailPageTest {
    private val account = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun transferDescriptionIncludesFeeOnlyAndBonusOnlyLegsAndMasksEachAmount() {
        val feeOnly = transfer(from = other, to = UUID.randomUUID(), fee = AccountAmount(account, Money(250, 2, "CNY")))
        val bonusOnly = transfer(from = other, to = UUID.randomUUID(), bonus = AccountAmount(account, Money(125, 2, "USD")))

        assertEquals("转账 · 手续费 2.50 CNY", accountRecordDescription(feeOnly, account, false))
        assertEquals("转账 · 返现 1.25 USD", accountRecordDescription(bonusOnly, account, false))
        assertEquals("转账 · 手续费 ••••", accountRecordDescription(feeOnly, account, true))
        assertEquals("转账 · 返现 ••••", accountRecordDescription(bonusOnly, account, true))
    }

    @Test
    fun transferDescriptionRetainsMultipleLegsForSameAccountWithoutCombiningCurrencies() {
        val input =
            transfer(
                from = account,
                to = other,
                fee = AccountAmount(account, Money(125, 2, "USD")),
                bonus = AccountAmount(account, Money(50, 2, "CNY")),
            )

        val description = accountRecordDescription(input, account, false)
        assertTrue(description.contains("转出 10.00 CNY"))
        assertTrue(description.contains("手续费 1.25 USD"))
        assertTrue(description.contains("返现 0.50 CNY"))
        assertEquals("转账 · 转出 ••••、手续费 ••••、返现 ••••", accountRecordDescription(input, account, true))
    }

    private fun transfer(
        from: UUID,
        to: UUID,
        fee: AccountAmount? = null,
        bonus: AccountAmount? = null,
    ) = RecordInput.Transfer(
        from = AccountAmount(from, Money(1000, 2, "CNY")),
        to = AccountAmount(to, Money(1000, 2, "CNY")),
        fee = fee,
        bonus = bonus,
    )
}
