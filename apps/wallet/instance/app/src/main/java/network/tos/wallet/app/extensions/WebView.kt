package network.tos.wallet.app.extensions

import android.annotation.SuppressLint
import network.tos.wallet.data.account.entities.WalletEntity
import uikit.widget.webview.WebViewFixed

@SuppressLint("RequiresFeature")
fun WebViewFixed.setWallet(wallet: WalletEntity) {
    val walletId = wallet.id.replace("-", "")
    setProfileName(walletId)
}