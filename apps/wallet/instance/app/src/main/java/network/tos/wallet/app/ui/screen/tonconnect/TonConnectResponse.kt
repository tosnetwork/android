package network.tos.wallet.app.ui.screen.tonconnect

import android.os.Parcelable
import network.tos.blockchain.ton.connect.TONProof
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeError
import network.tos.wallet.data.account.entities.WalletEntity
import kotlinx.parcelize.Parcelize

@Parcelize
data class TonConnectResponse(
    val notifications: Boolean,
    val proof: TONProof.Result?,
    val proofError: BridgeError?,
    val wallet: WalletEntity,
): Parcelable