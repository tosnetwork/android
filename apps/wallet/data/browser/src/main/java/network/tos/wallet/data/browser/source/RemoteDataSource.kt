package network.tos.wallet.data.browser.source

import android.util.Log
import network.tos.extensions.CrashReporter
import network.tos.wallet.api.API
import network.tos.wallet.data.browser.entities.BrowserDataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal class RemoteDataSource(
    private val api: API
) {

    suspend fun load(testnet: Boolean, locale: Locale): BrowserDataEntity? = withContext(Dispatchers.IO) {
        try {
            BrowserDataEntity(api.getBrowserApps(testnet, locale))
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            null
        }
    }
}