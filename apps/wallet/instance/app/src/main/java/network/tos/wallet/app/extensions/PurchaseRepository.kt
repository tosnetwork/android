package network.tos.wallet.app.extensions

import network.tos.wallet.app.Environment
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.purchase.PurchaseRepository
import network.tos.wallet.data.purchase.entity.OnRamp
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

data class OnRampResult(
    val data: OnRamp.Data,
    val country: String
)


suspend fun PurchaseRepository.getProvidersByCountry(
    wallet: WalletEntity,
    settingsRepository: SettingsRepository,
    country: String
): List<PurchaseMethodEntity> = withContext(Dispatchers.IO) {
    val methods = get(wallet.testnet, country, settingsRepository.getLocale()) ?: return@withContext emptyList()
    val all = methods.first + methods.second
    all.map { it.items }.flatten().distinctBy { it.title }
}