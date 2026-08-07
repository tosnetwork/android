package network.tos.wallet.app.ui.screen.events.spam

import android.os.Bundle
import android.util.Log
import android.view.View
import network.tos.wallet.app.core.history.list.HistoryAdapter
import network.tos.wallet.app.core.history.list.HistoryItemDecoration
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.uikit.list.ListPaginationListener
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class SpamEventsScreen(
    wallet: WalletEntity
): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {

    override val fragmentName: String = "SpamEventsScreen"

    override val viewModel: SpamEventsViewModel by walletViewModel()

    private val paginationListener = object : ListPaginationListener() {
        override fun onLoadMore() {
            viewModel.loadMore()
        }
    }

    private val legacyAdapter = HistoryAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(Localization.spam))
        setAdapter(legacyAdapter)
        addItemDecoration(HistoryItemDecoration())
        addScrollListener(paginationListener)

        collectFlow(viewModel.uiStateFlow) {
            setUiItems(it.uiItems)
        }
    }

    private fun setUiItems(uiItems: List<HistoryItem>) {
        val oldItemCount = legacyAdapter.itemCount
        legacyAdapter.submitList(uiItems) {
            if (2 >= oldItemCount && oldItemCount != 0) {
                scrollToTop()
            }
        }
    }

    companion object {

        fun newInstance(wallet: WalletEntity) = SpamEventsScreen(wallet)
    }
}