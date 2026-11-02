package network.tos.wallet.app.ui.screen.init

import network.tos.blockchain.ton.contract.WalletVersion
import org.junit.Assert.*
import org.junit.Test

class LegacyRecoverySelectionTest {
    private fun candidate(version: WalletVersion, funded: Boolean = false, known: Boolean = true) =
        LegacyRecoverySelection.Candidate(version.name, version, funded, known)

    @Test fun retainsEveryFundedVersionAndOmitsEmptyFallback() {
        val values = listOf(candidate(WalletVersion.V5R1), candidate(WalletVersion.V4R2, true),
            candidate(WalletVersion.V3R2, true), candidate(WalletVersion.V3R1))
        assertEquals(listOf(1, 2), LegacyRecoverySelection.select(values, WalletVersion.V5R1))
    }
    @Test fun missingOrIncompleteDiscoveryCannotHidePotentialAssets() {
        val values = listOf(candidate(WalletVersion.V5R1), candidate(WalletVersion.V4R2, known = false),
            candidate(WalletVersion.V3R1, true))
        assertEquals(listOf(1, 2), LegacyRecoverySelection.select(values, WalletVersion.V5R1))
    }
    @Test fun emptyAccountsUseOnlyOriginalProfileFallback() {
        val values = listOf(candidate(WalletVersion.V5R1), candidate(WalletVersion.V4R2), candidate(WalletVersion.TOSV5R1))
        assertEquals(listOf(0), LegacyRecoverySelection.select(values, WalletVersion.V5R1))
        assertEquals(listOf(2), LegacyRecoverySelection.select(values, WalletVersion.TOSV5R1))
    }
    @Test fun deployedZeroBalanceAccountKeepsItsOriginalAddress() {
        val values = listOf(candidate(WalletVersion.V5R1),
            candidate(WalletVersion.V4R2).copy(deployed = true))
        assertEquals(listOf(1), LegacyRecoverySelection.select(values, WalletVersion.V5R1))
    }
    @Test fun watchRecoveryRetainsItsExactAddressEvenIfUnfunded() {
        val values = listOf(candidate(WalletVersion.V5R1, true), candidate(WalletVersion.V4R2))
        assertEquals(listOf(1), LegacyRecoverySelection.select(values, WalletVersion.V5R1, WalletVersion.V4R2.name))
        assertThrows(IllegalArgumentException::class.java) {
            LegacyRecoverySelection.select(values, WalletVersion.V5R1, "missing-original-address")
        }
    }
    @Test fun invalidDiscoveryFailsInsteadOfChoosingAnUnrelatedWallet() {
        assertThrows(IllegalArgumentException::class.java) { LegacyRecoverySelection.select(emptyList(), WalletVersion.V5R1) }
        val one = candidate(WalletVersion.V4R2)
        assertThrows(IllegalArgumentException::class.java) { LegacyRecoverySelection.select(listOf(one, one), WalletVersion.V4R2) }
        assertThrows(IllegalArgumentException::class.java) { LegacyRecoverySelection.select(listOf(one), WalletVersion.V5R1) }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyRecoverySelection.select((0..64).map { one.copy(address = "$it") }, WalletVersion.V4R2)
        }
    }
    @Test fun multipleAccountsReceiveNumbersWithoutAutomaticVersionLabels() {
        assertEquals(listOf("Savings 3", "Savings 4"), LegacyRecoverySelection.names("Savings", 2, 2, false))
        assertEquals(listOf("Wallet 2"), LegacyRecoverySelection.names("Wallet", 1, 1, true))
        assertEquals(listOf("My v4 account"), LegacyRecoverySelection.names("My v4 account", 1, 3, false))
    }
}
