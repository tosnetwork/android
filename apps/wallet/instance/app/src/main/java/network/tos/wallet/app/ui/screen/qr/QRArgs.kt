package network.tos.wallet.app.ui.screen.qr

import android.os.Bundle
import network.tos.blockchain.ton.extensions.toUserFriendly
import network.tos.extensions.getEnum
import network.tos.extensions.getParcelableCompat
import network.tos.extensions.putEnum
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.Wallet
import uikit.base.BaseArgs

data class QRArgs(
    val address: String,
    val token: TokenEntity,
    val walletType: Wallet.Type
): BaseArgs() {

    private companion object {
        private const val ARG_ADDRESS = "address"
        private const val ARG_TOKEN = "token"
        private const val ARG_WALLET_TYPE = "wallet_type"
    }

    constructor(bundle: Bundle) : this(
        address = bundle.getString(ARG_ADDRESS)!!,
        token = bundle.getParcelableCompat(ARG_TOKEN)!!,
        walletType = bundle.getEnum<Wallet.Type>(ARG_WALLET_TYPE, Wallet.Type.Default)
    )

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putString(ARG_ADDRESS, address)
        bundle.putParcelable(ARG_TOKEN, token)
        bundle.putEnum(ARG_WALLET_TYPE, walletType)
        return bundle
    }
}