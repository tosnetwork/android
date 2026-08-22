package network.tos.wallet.app.ui.screen.nft

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.blockchain.ton.extensions.storeAddress
import network.tos.blockchain.ton.dns.TosDnsOperation
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.dns.TosDnsTransactionBuilder
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.collectibles.entities.NftEntity
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.settings.entities.TokenPrefsEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uikit.extensions.activity
import uikit.navigation.Navigation.Companion.navigation
import org.ton.cell.CellBuilder
import java.math.BigInteger

class NftViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val nft: NftEntity,
    private val settingsRepository: SettingsRepository,
    private val api: API,
    private val collectiblesRepository: CollectiblesRepository,
): BaseWalletVM(app) {

    private val walletDnsCategory = BigInteger(
        "e8d44050873dba865aa7c170ab4cce64d90839a34dcfd6cf71d14e0205443b1b",
        16,
    )

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

    val domainStateFlow = flow {
        if (nft.isDomain && !nft.isTelegramUsername) {
            emit(api.tos.inspectDnsDomain(nft.name, wallet.testnet))
        }
    }.flowOn(Dispatchers.IO).catch { }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun renewDomain() {
        submitDomainAction { TosDnsTransactionBuilder.Action.Renew() }
    }

    fun bidMinimum() {
        submitDomainAction { state ->
            TosDnsTransactionBuilder.Action.Bid(TosDnsOperation.minimumNextBid(state.maximumBid))
        }
    }

    fun finishAuction() {
        submitDomainAction { TosDnsTransactionBuilder.Action.FinishAuction }
    }

    fun releaseDomain() {
        submitDomainAction { state ->
            TosDnsTransactionBuilder.Action.Release(
                TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, System.currentTimeMillis() / 1000),
            )
        }
    }

    fun setWalletRecord() {
        submitDomainAction {
            val value = CellBuilder.createCell {
                storeUInt(0x9fd3, 16)
                storeAddress(wallet.contract.address)
                storeUInt(0, 8)
            }
            TosDnsTransactionBuilder.Action.ChangeRecord(walletDnsCategory, value)
        }
    }

    fun deleteWalletRecord() {
        submitDomainAction { TosDnsTransactionBuilder.Action.ChangeRecord(walletDnsCategory, null) }
    }

    private fun submitDomainAction(action: (network.tos.wallet.api.tos.TosDnsDomainState) -> TosDnsTransactionBuilder.Action) {
        viewModelScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    api.tos.inspectDnsDomain(nft.name, wallet.testnet)
                }
                require(state.itemAddress.equalsAddress(nft.address)) { "DNS item identity changed" }
                val request = TosDnsTransactionBuilder.createSignRequest(
                    wallet,
                    state,
                    action(state),
                )
                SendTransactionScreen.run(context, wallet, request)
                toast(Localization.dns_action_done)
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
