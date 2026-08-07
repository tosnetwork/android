package network.tos.wallet.app.ui.screen.root

import android.net.Uri
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeEvent
import network.tos.wallet.data.dapps.entities.AppConnectEntity

data class RootSignTransaction(
    val connection: AppConnectEntity,
    val message: BridgeEvent.Message,
    val returnUri: Uri?
) {

    val hash: String
        get() = "${connection.clientId}_${message.id}"

    val id: Long
        get() = message.id

    val params: List<String>
        get() = message.params
}