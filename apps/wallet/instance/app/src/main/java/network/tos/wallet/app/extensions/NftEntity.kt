package network.tos.wallet.app.extensions

import network.tos.wallet.data.collectibles.entities.NftEntity
import network.tos.wallet.data.core.Trust
import network.tos.wallet.data.settings.entities.TokenPrefsEntity

fun NftEntity.with(pref: TokenPrefsEntity): NftEntity {
    if (pref.isTrust) {
        return copy(trust = Trust.whitelist)
    }
    return this
}
