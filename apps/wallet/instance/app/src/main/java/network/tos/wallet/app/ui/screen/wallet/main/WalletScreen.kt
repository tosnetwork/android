package network.tos.wallet.app.ui.screen.wallet.main

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.component.MainRecyclerView
import network.tos.wallet.app.ui.component.wallet.WalletHeaderView
import network.tos.wallet.app.ui.screen.main.MainScreen
import network.tos.wallet.app.ui.screen.wallet.picker.PickerScreen
import network.tos.wallet.app.ui.screen.settings.main.SettingsScreen
import network.tos.wallet.app.ui.screen.wallet.main.list.Item.Status
import network.tos.wallet.app.ui.screen.wallet.main.list.WalletAdapter
import network.tos.wallet.app.R
import network.tos.wallet.data.account.entities.WalletEntity
import kotlinx.coroutines.flow.filterNotNull
import uikit.drawable.BarDrawable
import uikit.extensions.collectFlow

class WalletScreen(wallet: WalletEntity): MainScreen.Child(R.layout.fragment_wallet, wallet) {

    override val fragmentName: String = "WalletScreen"

    override val viewModel: WalletViewModel by walletViewModel()

    private val adapter = WalletAdapter()

    private lateinit var headerView: WalletHeaderView
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var listView: MainRecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerView = view.findViewById(R.id.header)
        headerView.onWalletClick = { navigation?.add(PickerScreen.newInstance(from = "wallet")) }
        headerView.onSettingsClick = { navigation?.add(SettingsScreen.newInstance(wallet, "wallet")) }
        headerView.doWalletSwipe = { right ->
            if (right) {
                viewModel.prevWallet()
            } else {
                viewModel.nextWallet()
            }
        }

        refreshLayout = view.findViewById(R.id.refresh)
        refreshLayout.setOnRefreshListener { viewModel.refresh() }

        listView = view.findViewById(R.id.list)
        listView.adapter = adapter

        collectFlow(viewModel.uiLabelFlow.filterNotNull(), headerView::setWallet)
        collectFlow(viewModel.hasBackupFlow, headerView::setDot)
        collectFlow(viewModel.statusFlow) { status ->
            if (refreshLayout.isRefreshing && status != Status.Updating) {
                refreshLayout.isRefreshing = false
            }
        }
    }

    override fun getRecyclerView(): RecyclerView? {
        if (this::listView.isInitialized) {
            return listView
        }
        return null
    }

    override fun getTopBarDrawable(): BarDrawable? {
        if (this::headerView.isInitialized) {
            return headerView.background as? BarDrawable
        }
        return null
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = WalletScreen(wallet)
    }
}
