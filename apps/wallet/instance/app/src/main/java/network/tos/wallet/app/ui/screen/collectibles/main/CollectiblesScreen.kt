package network.tos.wallet.app.ui.screen.collectibles.main

import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.extensions.isLightTheme
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.UiListState
import network.tos.wallet.app.ui.screen.collectibles.main.list.Adapter
import network.tos.wallet.app.ui.screen.collectibles.manage.CollectiblesManageScreen
import network.tos.wallet.app.ui.screen.main.MainScreen
import network.tos.wallet.app.ui.screen.qr.QRScreen
import network.tos.wallet.app.popup.ActionSheet
import network.tos.wallet.app.R
import network.tos.uikit.color.backgroundPageColor
import network.tos.uikit.color.backgroundTransparentColor
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.api.tos.TosDnsLifecycle
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import uikit.drawable.BarDrawable
import uikit.extensions.collectFlow
import uikit.widget.EmptyLayout
import uikit.widget.HeaderView

class CollectiblesScreen(wallet: WalletEntity): MainScreen.Child(R.layout.fragment_main_list, wallet) {

    override val fragmentName: String = "CollectiblesScreen"

    override val viewModel: CollectiblesViewModel by walletViewModel()

    private val adapter = Adapter()

    private lateinit var headerView: HeaderView
    private lateinit var refreshView: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: EmptyLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerView = view.findViewById(R.id.header)
        headerView.title = getString(Localization.collectibles)
        headerView.setTitleGravity(Gravity.START)
        headerView.hideCloseIcon()
        if (requireContext().isLightTheme) {
            headerView.setColor(requireContext().backgroundPageColor)
        } else {
            headerView.setColor(requireContext().backgroundTransparentColor)
        }

        refreshView = view.findViewById(R.id.refresh)
        refreshView.setOnRefreshListener { viewModel.refresh() }

        listView = view.findViewById(R.id.list)
        listView.updatePadding(top = 0)
        listView.layoutManager = object : GridLayoutManager(context, 3) {
            override fun supportsPredictiveItemAnimations(): Boolean = false
        }
        listView.adapter = adapter

        emptyView = view.findViewById(R.id.empty)
        emptyView.doOnButtonClick = { openQRCode() }

        collectFlow(viewModel.uiListStateFlow) { state ->
            when (state) {
                is UiListState.Loading -> {
                    removeActionIcons()
                    adapter.applySkeleton()
                    headerView.setSubtitle(Localization.updating)
                }
                is UiListState.Empty -> {
                    if (viewModel.hasNfts) {
                        applyActionIcons()
                    } else {
                        removeActionIcons()
                    }
                    refreshView.isRefreshing = false
                    setEmptyState()
                    headerView.setSubtitle(null)
                }
                is UiListState.Items -> {
                    applyActionIcons()
                    setListState()
                    adapter.submitList(state.items) {
                        if (!state.cache) {
                            headerView.setSubtitle(null)
                            refreshView.isRefreshing = false
                        }
                    }
                }
            }
        }
    }

    private fun applyActionIcons() {
        headerView.setAction(UIKitIcon.ic_sliders_16)
        headerView.doOnActionClick =(::showCollectiblesActions)
        headerView.setRightButton(Localization.spam) {
            navigation?.add(CollectiblesManageScreen.newInstance(wallet, true))
        }
    }

    private fun showCollectiblesActions(anchor: View) {
        val actions = ActionSheet(requireContext())
        actions.addItem(MANAGE_ID, Localization.collectibles_manage, UIKitIcon.ic_sliders_16)
        if (!wallet.isWatchOnly) {
            actions.addItem(REGISTER_DOMAIN_ID, Localization.dns_register, UIKitIcon.ic_plus_alternate_16)
            actions.addItem(MANAGE_DOMAIN_ID, Localization.dns_manage_domain, UIKitIcon.ic_refresh_16)
        }
        actions.doOnItemClick = { item ->
            when (item.id) {
                MANAGE_ID -> navigation?.add(CollectiblesManageScreen.newInstance(wallet))
                REGISTER_DOMAIN_ID -> promptDomainRegistration()
                MANAGE_DOMAIN_ID -> promptDomainManagement()
            }
        }
        actions.show(anchor)
    }

    private fun promptDomainRegistration() {
        val input = AppCompatEditText(requireContext()).apply {
            hint = "alice.tos"
            isSingleLine = true
        }
        AppCompatAlertDialog.Builder(requireContext())
            .setTitle(Localization.dns_register)
            .setMessage(Localization.dns_register_warning)
            .setView(input)
            .setNegativeButton(Localization.cancel, null)
            .setPositiveButton(Localization.continue_action) { _, _ ->
                viewModel.inspectDomainRegistration(input.text?.toString().orEmpty()) { preview ->
                    if (preview == null) {
                        navigation?.toast(Localization.dns_action_failed)
                    } else {
                        confirmDomainRegistration(preview)
                    }
                }
            }
            .show()
    }

    private fun confirmDomainRegistration(preview: CollectiblesViewModel.DomainRegistrationPreview) {
        val amount = preview.amount.toBigDecimal().movePointLeft(9).stripTrailingZeros().toPlainString()
        val network = if (preview.testnet) "testnet" else "mainnet"
        AppCompatAlertDialog.Builder(requireContext())
            .setTitle(Localization.dns_register)
            .setMessage(getString(
                Localization.dns_register_confirmation,
                preview.canonicalName,
                network,
                preview.collectionAddress,
                amount,
                preview.checkpointSequence,
                preview.checkpointAge,
            ))
            .setNegativeButton(Localization.cancel, null)
            .setPositiveButton(Localization.continue_action) { _, _ ->
                viewModel.registerDomain(preview) { success ->
                    navigation?.toast(if (success) Localization.dns_action_done else Localization.dns_action_failed)
                }
            }
            .show()
    }

    private fun promptDomainManagement() {
        val input = AppCompatEditText(requireContext()).apply {
            hint = "alice.tos"
            isSingleLine = true
        }
        AppCompatAlertDialog.Builder(requireContext())
            .setTitle(Localization.dns_manage_domain)
            .setMessage(Localization.dns_manage_prompt)
            .setView(input)
            .setNegativeButton(Localization.cancel, null)
            .setPositiveButton(Localization.continue_action) { _, _ ->
                viewModel.inspectDomainManagement(input.text?.toString().orEmpty()) { preview ->
                    if (preview == null) {
                        navigation?.toast(Localization.dns_action_failed)
                    } else {
                        confirmDomainManagement(preview)
                    }
                }
            }
            .show()
    }

    private fun confirmDomainManagement(preview: CollectiblesViewModel.DomainManagementPreview) {
        val action = when (preview.lifecycle) {
            TosDnsLifecycle.AUCTION -> getString(Localization.dns_bid_minimum)
            TosDnsLifecycle.AUCTION_ENDED -> getString(Localization.dns_finish_auction)
            TosDnsLifecycle.RELEASABLE -> getString(Localization.dns_release_domain)
            else -> return
        }
        val amount = preview.amount.toBigDecimal().movePointLeft(9).stripTrailingZeros().toPlainString()
        val network = if (preview.testnet) "testnet" else "mainnet"
        AppCompatAlertDialog.Builder(requireContext())
            .setTitle(Localization.dns_manage_domain)
            .setMessage(getString(
                Localization.dns_manage_warning,
                action,
                preview.canonicalName,
                network,
                preview.targetAddress,
                amount,
                preview.checkpointSequence,
                preview.checkpointAge,
            ))
            .setNegativeButton(Localization.cancel, null)
            .setPositiveButton(Localization.continue_action) { _, _ ->
                viewModel.manageDomain(preview) { success ->
                    navigation?.toast(if (success) Localization.dns_action_done else Localization.dns_action_failed)
                }
            }
            .show()
    }

    private fun removeActionIcons() {
        headerView.setAction(0)
        headerView.doOnActionClick = null
        headerView.setRightContent(null)
    }

    private fun openQRCode() {
        navigation?.add(QRScreen.newInstance(screenContext.wallet))
    }

    private fun setEmptyState() {
        if (emptyView.visibility == View.VISIBLE) {
            return
        }
        emptyView.visibility = View.VISIBLE
        listView.visibility = View.GONE
    }

    private fun setListState() {
        if (listView.visibility == View.VISIBLE) {
            return
        }
        emptyView.visibility = View.GONE
        listView.visibility = View.VISIBLE
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

        private const val MANAGE_ID = 1L
        private const val REGISTER_DOMAIN_ID = 2L
        private const val MANAGE_DOMAIN_ID = 3L

        fun newInstance(wallet: WalletEntity) = CollectiblesScreen(wallet)
    }
}
