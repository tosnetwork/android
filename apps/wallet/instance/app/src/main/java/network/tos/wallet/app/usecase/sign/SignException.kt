package network.tos.wallet.app.usecase.sign

import network.tos.wallet.data.account.Wallet

sealed class SignException(message: String): Exception(message) {

    data class UnsupportedWalletType(
        val type: Wallet.Type
    ): SignException("Unsupported wallet type: $type")



}