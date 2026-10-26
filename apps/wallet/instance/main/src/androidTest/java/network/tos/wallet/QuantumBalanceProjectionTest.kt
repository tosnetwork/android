package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.app.manager.assets.QuantumBalanceProjection
import network.tos.wallet.data.account.pq.QuantumSelfPayAccount
import network.tos.wallet.data.core.currency.WalletCurrency
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

@RunWith(AndroidJUnit4::class)
class QuantumBalanceProjectionTest {
    @Test fun fullUnsignedCoinRangeRetainsEveryNanoWithoutFloatingPointOrDecimalRounding() {
        val amount = BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE)
        val snapshot = QuantumSelfPayAccount.Snapshot(QuantumSelfPayAccount.Status.Uninitialized, amount, BigInteger.valueOf(128), 100)
        val projected = QuantumBalanceProjection.token("0:" + "42".repeat(32), snapshot, WalletCurrency.TON)
        assertEquals(amount, projected.balance.value.toBigInteger())
        assertEquals(amount.toString(), projected.balance.value.toNano(9))
        assertEquals(projected.balance.value, projected.fiat)
        assertFalse(projected.balance.initializedAccount)
    }

    @Test fun absentAndFrozenAreNotConflatedWithAnActiveSpendableWallet() {
        for (status in listOf(QuantumSelfPayAccount.Status.Absent, QuantumSelfPayAccount.Status.Frozen(ByteArray(32)))) {
            val projected = QuantumBalanceProjection.token("0:" + "42".repeat(32),
                QuantumSelfPayAccount.Snapshot(status, BigInteger.ZERO, BigInteger.ZERO, 0), WalletCurrency.TON)
            assertEquals(BigInteger.ZERO, projected.balance.value.toBigInteger())
            assertEquals(status is QuantumSelfPayAccount.Status.Frozen, projected.balance.initializedAccount)
        }
    }
}
