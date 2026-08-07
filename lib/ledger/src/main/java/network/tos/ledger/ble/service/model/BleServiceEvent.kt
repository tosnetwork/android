package network.tos.ledger.ble.service.model

import network.tos.ledger.ble.model.BleError

sealed class BleServiceEvent {
    object BleDeviceConnected: BleServiceEvent()
    data class BleDeviceDisconnected(val error: BleError? = null): BleServiceEvent()
    data class SuccessSend(val sendId: String): BleServiceEvent()
    data class SendAnswer(val sendId: String, val answer: String): BleServiceEvent()
    data class ErrorSend(val sendId: String, val error: String): BleServiceEvent()
}
