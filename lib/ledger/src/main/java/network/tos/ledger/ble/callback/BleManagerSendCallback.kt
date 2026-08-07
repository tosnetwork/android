package network.tos.ledger.ble.callback

data class BleManagerSendCallback(
    val id: String,
    val onSuccess: (String) -> Unit,
    val onError: (String) -> Unit,
)
