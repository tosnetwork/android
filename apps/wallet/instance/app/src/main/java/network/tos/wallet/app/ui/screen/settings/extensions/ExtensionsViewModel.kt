package network.tos.wallet.app.ui.screen.settings.extensions

import android.app.Application
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.settings.extensions.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.plugins.PluginsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ExtensionsViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val pluginsRepository: PluginsRepository,
) : BaseWalletVM(app) {

    val uiItemsFlow = pluginsRepository.updatedFlow.map { _ ->
        val plugins =
            pluginsRepository.getPlugins(wallet.accountId, wallet.testnet, refresh = false)
        plugins.mapIndexed { index, plugin ->
            Item.Plugin(
                plugin = plugin,
                wallet = wallet,
                position = ListCell.getPosition(plugins.size, index)
            )
        }
    }.flowOn(Dispatchers.IO)
}



