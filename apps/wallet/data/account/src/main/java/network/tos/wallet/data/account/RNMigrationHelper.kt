package network.tos.wallet.data.account

import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.data.rn.data.RNVaultState

internal class RNMigrationHelper(
    private val rnLegacy: RNLegacy
) {

    suspend fun loadSecureStore(passcode: String): RNVaultState {
        return rnLegacy.getVaultState(passcode)
    }

    suspend fun loadLegacy(): Pair<String, List<WalletEntity>> {
        val legacyWallets = rnLegacy.getWallets()
        var selectedIdentifier = legacyWallets.selectedIdentifier
        if (legacyWallets.wallets.isEmpty()) {
            return Pair("", emptyList())
        }
        val list = mutableListOf<WalletEntity>()
        for (legacyWallet in legacyWallets.wallets) {
            val entity = legacyWallet.toWalletEntity()
            if (entity == null) {
                if (selectedIdentifier == legacyWallet.identifier) selectedIdentifier = ""
                continue
            }
            list.add(entity)
        }
        if (selectedIdentifier.isEmpty()) {
            val wallet = list.firstOrNull() ?: return Pair("", emptyList())
            selectedIdentifier = wallet.id
        }
        return Pair(selectedIdentifier, list)
    }
}