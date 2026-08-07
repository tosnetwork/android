package network.tos.wallet.app.core.entities

import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.settings.entities.WalletPrefsEntity

data class WalletExtendedEntity(
    val raw: WalletEntity,
    val prefs: WalletPrefsEntity,
) {

    val index: Int
        get() = prefs.index
}