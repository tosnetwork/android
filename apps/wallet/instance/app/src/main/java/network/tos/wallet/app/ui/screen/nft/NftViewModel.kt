package network.tos.wallet.app.ui.screen.nft

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.extensions.currentTimeSeconds
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.dns.renew.DNSRenewViewModel
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.collectibles.entities.NftEntity
import network.tos.wallet.data.core.entity.SignRequestEntity
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.settings.entities.TokenPrefsEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uikit.extensions.activity
import uikit.navigation.Navigation.Companion.navigation

class NftViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val nft: NftEntity,
    private val settingsRepository: SettingsRepository,
    private val api: API,
    private val collectiblesRepository: CollectiblesRepository,
): BaseWalletVM(app) {

    val burnAddress: String by lazy {
        api.getBurnAddress()
    }

    val expiresFlow = flow {
        if (nft.isDomain && !nft.isTelegramUsername && !wallet.isWatchOnly) {
            collectiblesRepository.getDnsNftExpiring(
                accountId = wallet.accountId,
                testnet = wallet.testnet,
                nftAddress = nft.address
            )?.let { emit(it) }
        }
    }

    fun renewDomain() {
        val request = SignRequestEntity.Builder()
            .setValidUntil(currentTimeSeconds() + 10 * 60)
            .setTestnet(wallet.testnet)
            .addMessage(DNSRenewViewModel.createMessage(nft.address))
            .setFrom(wallet.contract.address)
            .build(Uri.EMPTY)

        viewModelScope.launch {
            try {
                SendTransactionScreen.run(context, wallet, request)
                toast(Localization.renew_dns_done)
                getNft()?.let {
                    context.activity?.addScreenDelay(NftScreen.newInstance(wallet, it))
                }
                finish()
            } catch (ignored: Throwable) { }
        }
    }

    private fun getNft() = collectiblesRepository.getNft(wallet.id, wallet.testnet, nft.address)

    fun reportSpam(spam: Boolean, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = if (spam) TokenPrefsEntity.State.SPAM else TokenPrefsEntity.State.TRUST
            val address = nft.collectionAddressOrNFTAddress
            settingsRepository.setTokenState(wallet.id, address, state)
            try {
                api.reportNtfSpam(nft.address, spam)
            } catch (ignored: Throwable) {}
            withContext(Dispatchers.Main) {
                callback()
            }
        }
    }

    fun hideCollection(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val address = nft.collection?.address ?: nft.address
            settingsRepository.setTokenHidden(wallet.id, address, true)
            withContext(Dispatchers.Main) {
                callback()
            }
        }
    }
}