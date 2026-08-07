package network.tos.wallet.app.ui.screen.external.qr

import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.base.QRCameraScreen
import network.tos.ur.URDecoder
import network.tos.ur.registry.RegistryItem
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import uikit.navigation.Navigation.Companion.navigation

private fun fixReceivedURPart(part: String): String {
    if (part.startsWith("http://", ignoreCase = true)) {
        return part.removePrefix("http://")
    } else if (part.startsWith("https://", ignoreCase = true)) {
        return part.removePrefix("https://")
    }
    return part
}

@Suppress("UNCHECKED_CAST")
fun <R: RegistryItem> QRCameraScreen.urFlow(): Flow<R> {
    val urDecoder = URDecoder()

    return readerFlow.map { urDecoder.receivePart(fixReceivedURPart(it)) }
        .filter { it }
        .map { urDecoder.result }
        .filter { it.type == network.tos.ur.ResultType.SUCCESS }
        .map { it.ur.decodeFromRegistry() as R }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .catch { navigation?.toast(Localization.unknown_error) }
}
