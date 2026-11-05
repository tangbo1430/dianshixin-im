package dev.icos.app.data.local

import dev.icos.app.domain.Money
import dev.icos.app.domain.MoneyException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AccountPostingAmountTest {
    @Test
    fun conflictResolutionDistinguishesRemoteOriginAndRequiresExactLocalPairing() {
        val owner = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003")
        val epoch = java.util.UUID.fromString("00000000-0000-0000-0000-000000000004")
        val op = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
        val foreignOwner = java.util.UUID.randomUUID().toString()
        val intent = AccountMutationIntentEntity(op.toString(), owner.toString(), epoch.toString(), "record", "aggregate",
            "record.create", "{}", "CONFLICT", 10)
        val outbox = TypedOutboxEntity(opId = op.toString(), groupId = "00000000-0000-0000-0000-000000000002",
            ownerId = owner.toString(), epochId = epoch.toString(), deviceId = "00000000-0000-0000-0000-000000000005",
            aggregateType = "record", aggregateId = "aggregate", baseVersionsJson = "{}",
            commandType = "record.create", schemaVersion = 1, payloadJson = "{}", state = "CONFLICT", createdAt = 10)
        assertEquals(false, AccountMutationRepository.conflictResolutionHasLocalOrigin(null, null, owner, epoch, op))
        assertEquals(true, AccountMutationRepository.conflictResolutionHasLocalOrigin(intent, outbox, owner, epoch, op))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AccountMutationRepository.conflictResolutionHasLocalOrigin(intent.copy(ownerId = foreignOwner), outbox, owner, epoch, op)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AccountMutationRepository.conflictResolutionHasLocalOrigin(intent, outbox.copy(epochId = java.util.UUID.randomUUID().toString()), owner, epoch, op)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AccountMutationRepository.conflictResolutionHasLocalOrigin(intent, outbox.copy(state = "QUEUED"), owner, epoch, op)
        }
    }

    @Test
    fun assetAccountsStoreTheAssetFlowUnchanged() {
        assertEquals(Money(-10000, 2, "CNY"), liabilityAwareStoredAmount("ASSET", Money(-10000, 2, "CNY")))
        assertEquals(Money(3000, 2, "CNY"), liabilityAwareStoredAmount("ASSET", Money(3000, 2, "CNY")))
        assertEquals(Money(0, 0, "CNY"), liabilityAwareStoredAmount("CASH", Money(0, 0, "CNY")))
        assertEquals(Money(-250, 2, "USD"), liabilityAwareStoredAmount("BANK", Money(-250, 2, "USD")))
    }

    @Test
    fun liabilityAccountsStoreTheOppositeSoPositiveMeansDebt() {
        // expense 100 on a liability account: asset flow -100, owed +100
        assertEquals(Money(10000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", Money(-10000, 2, "CNY")))
        // income 30 to a liability account: asset flow +30, owed -30
        assertEquals(Money(-3000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", Money(3000, 2, "CNY")))
    }

    @Test
    fun allFourTransferDirectionsAdaptOnceAtThePostingBoundary() {
        val from100 = Money(-10000, 2, "CNY")
        val to100 = Money(10000, 2, "CNY")
        // ASSET -> ASSET: -100 / +100
        assertEquals(from100, liabilityAwareStoredAmount("ASSET", from100))
        assertEquals(to100, liabilityAwareStoredAmount("ASSET", to100))
        // ASSET -> LIABILITY repayment 100: asset -100 / debt -100
        assertEquals(from100, liabilityAwareStoredAmount("ASSET", from100))
        assertEquals(Money(-10000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", to100))
        // LIABILITY -> ASSET draw 100: debt +100 / asset +100
        assertEquals(Money(10000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", from100))
        assertEquals(to100, liabilityAwareStoredAmount("ASSET", to100))
        // LIABILITY A -> LIABILITY B transfer 100: debtA +100 / debtB -100
        assertEquals(Money(10000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", from100))
        assertEquals(Money(-10000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", to100))
    }

    @Test
    fun feesAndBonusesUseTheSameRuleAsTheirChildExpenseAndIncome() {
        // fee 2 charged to a liability account obliges +2; the expense side stays 2.
        assertEquals(Money(200, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", Money(-200, 2, "CNY")))
        // bonus 1 received on a liability account reduces debt by 1; the income side stays 1.
        assertEquals(Money(-100, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", Money(100, 2, "CNY")))
    }

    @Test
    fun overpaymentKeepsPrepaidDebtFiniteWithoutClamping() {
        // debt 500 settled with 700 cash: the liability leg stores -700, leaving a 200 prepaid balance.
        assertEquals(Money(-70000, 2, "CNY"), liabilityAwareStoredAmount("LIABILITY", Money(70000, 2, "CNY")))
    }

    @Test
    fun conversionPreservesExactUnscaledScaleAndCurrency() {
        val stored = liabilityAwareStoredAmount("LIABILITY", Money(-1, 6, "JPY"))
        assertEquals(1L, stored.unscaled)
        assertEquals(6, stored.scale)
        assertEquals("JPY", stored.currency)
        assertEquals(Money(1500000000000000000L, 6, "USD"), liabilityAwareStoredAmount("LIABILITY", Money(-1500000000000000000L, 6, "USD")))
    }

    @Test
    fun overflowSurfacesTheHousesErrorAndNoSilentWrap() {
        try {
            liabilityAwareStoredAmount("LIABILITY", Money(Long.MIN_VALUE, 0, "CNY"))
            fail("negating Long.MIN_VALUE must surface the house overflow error")
        } catch (failure: MoneyException) {
            assertEquals("AMOUNT_OVERFLOW", failure.code)
        }
        // Asset accounts never negate, so the extreme value still passes straight through.
        assertEquals(Money(Long.MIN_VALUE, 0, "CNY"), liabilityAwareStoredAmount("ASSET", Money(Long.MIN_VALUE, 0, "CNY")))
    }
}
