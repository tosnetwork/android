package network.tos.wallet.app.ui.screen.ledger.steps

import network.tos.ledger.ble.model.BleError

sealed class ConnectionState {
    data object Idle: ConnectionState()
    data object Scanning: ConnectionState()
    data object Connected: ConnectionState()
    data object TonAppOpened: ConnectionState()
    data object Signed: ConnectionState()
    data class Disconnected(val error: BleError? = null): ConnectionState()
}