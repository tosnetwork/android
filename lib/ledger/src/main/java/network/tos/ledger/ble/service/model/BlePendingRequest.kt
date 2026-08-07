package network.tos.ledger.ble.service.model

data class BlePendingRequest(
    val id: String,
    val apdu: ByteArray
)
