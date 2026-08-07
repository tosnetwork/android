package network.tos.ledger.ble.callback

import network.tos.ledger.ble.model.BleDeviceModel
import network.tos.ledger.ble.model.BleError

interface BleManagerConnectionCallback {
    fun onConnectionSuccess(device: BleDeviceModel)
    fun onConnectionError(error: BleError)
}
