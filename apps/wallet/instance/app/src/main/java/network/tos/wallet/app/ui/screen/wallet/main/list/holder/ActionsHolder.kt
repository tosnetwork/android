package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.koin.serverFlags
import network.tos.wallet.app.ui.screen.camera.CameraScreen
import network.tos.wallet.app.ui.screen.onramp.main.OnRampScreen
import network.tos.wallet.app.ui.screen.qr.QRScreen
import network.tos.wallet.app.ui.screen.send.main.SendScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.swap.SwapScreen
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.ui.screen.watchonly.WatchInfoScreen
import network.tos.wallet.app.R
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.account.entities.WalletEntity
import uikit.widget.IconButtonView

class ActionsHolder(parent: ViewGroup): Holder<Item.Actions>(parent, R.layout.view_wallet_actions) {

    private val sendView = findViewById<IconButtonView>(R.id.send)
    private val receiveView = findViewById<IconButtonView>(R.id.receive)
    private val scanView = findViewById<IconButtonView>(R.id.scan)

    override fun onBind(item: Item.Actions) {
        val isWatchOnly = item.walletType == Wallet.Type.Watch
        val isSendEnabled = item.walletType != Wallet.Type.Watch
        val isScanEnabled = item.walletType != Wallet.Type.Watch

        scanView.setOnClickListener {
            if (isWatchOnly) {
                openWatchInfo(item.wallet)
                return@setOnClickListener
            } else if (!isScanEnabled) {
                return@setOnClickListener
            }

            val chains = mutableListOf(Blockchain.TON)

            if (item.tronEnabled) {
                chains.add(Blockchain.TRON)
            }

            navigation?.add(CameraScreen.newInstance(chains = chains))
        }
        receiveView.setOnClickListener {
            navigation?.add(QRScreen.newInstance(item.wallet))
        }

        sendView.setOnClickListener {
            if (isWatchOnly) {
                openWatchInfo(item.wallet)
                return@setOnClickListener
            } else if (!isSendEnabled) {
                return@setOnClickListener
            }

            navigation?.add(SendScreen.newInstance(item.wallet, type = SendScreen.Companion.Type.Default))
        }
        sendView.setEnabledAlpha(isSendEnabled)
        scanView.setEnabledAlpha(isScanEnabled)
    }

    private fun openWatchInfo(wallet: WalletEntity) {
        navigation?.add(WatchInfoScreen.newInstance(wallet))
    }

}
