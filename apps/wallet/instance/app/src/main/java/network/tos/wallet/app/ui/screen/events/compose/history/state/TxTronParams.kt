package network.tos.wallet.app.ui.screen.events.compose.history.state

import network.tos.wallet.api.entity.value.BlockchainAddress

data class TxTronParams(
    val address: BlockchainAddress? = null,
    val tonProofToken: String? = null
) {

    val isEmtpy: Boolean
        get() = address == null && tonProofToken == null
}