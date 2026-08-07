package network.tos.ledger.ton

import android.os.Parcelable
import network.tos.ledger.devices.DeviceModel
import kotlinx.parcelize.Parcelize

@Parcelize
data class LedgerConnectData(
    val accounts: List<LedgerAccount>,
    val deviceId: String,
    val model: DeviceModel
): Parcelable
