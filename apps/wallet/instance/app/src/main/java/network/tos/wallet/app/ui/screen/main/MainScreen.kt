package network.tos.wallet.app.ui.screen.main

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import network.tos.extensions.CrashReporter
import network.tos.extensions.query
import network.tos.wallet.app.extensions.isLightTheme
import network.tos.wallet.app.extensions.removeAllFragments
import network.tos.wallet.app.koin.serverFlags
import network.tos.wallet.app.ui.base.BaseWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.app.ui.screen.browser.base.BrowserBaseScreen
import network.tos.wallet.app.R
import network.tos.wallet.app.ui.screen.root.RootViewModel
import network.tos.wallet.app.ui.screen.collectibles.main.CollectiblesScreen
import network.tos.wallet.app.ui.screen.events.main.EventsScreen
import network.tos.wallet.app.ui.screen.events.compose.history.TxEventsScreen
import network.tos.wallet.app.ui.screen.wallet.picker.PickerScreen
import network.tos.wallet.app.ui.screen.root.RootEvent
import network.tos.wallet.app.ui.screen.swap.SwapScreen
import network.tos.wallet.app.ui.screen.wallet.main.WalletScreen
import network.tos.uikit.color.backgroundPageColor
import network.tos.uikit.color.backgroundTransparentColor
import network.tos.uikit.color.constantBlackColor
import network.tos.uikit.color.drawable
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.androidx.viewmodel.ext.android.getViewModel
import uikit.base.BaseFragment
import uikit.drawable.BarDrawable
import uikit.extensions.activity
import uikit.extensions.collectFlow
import uikit.extensions.isMaxScrollReached
import uikit.extensions.roundTop
import uikit.extensions.scale
import uikit.utils.RecyclerVerticalScrollListener
import uikit.widget.BottomTabsView
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainScreen: BaseWalletScreen<ScreenContext.None>(R.layout.fragment_main, ScreenContext.None) {

    override val fragmentName: String = "MainScreen"

    abstract class Child(
        @LayoutRes layoutId: Int,
        wallet: WalletEntity,
    ): WalletContextScreen(layoutId, wallet) {

        val mainViewModel: MainViewModel by lazy {
            requireParentFragment().getViewModel()
        }

        private val scrollListener = object : RecyclerVerticalScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, verticalScrollOffset: Int) {
                recyclerView.postOnAnimation {
                    if (recyclerView.isAttachedToWindow) {
                        getTopBarDrawable()?.setDivider(verticalScrollOffset > 0)
                        mainViewModel.setBottomScrolled(!recyclerView.isMaxScrollReached)
                    }
                }
            }
        }

        abstract fun getRecyclerView(): RecyclerView?

        abstract fun getTopBarDrawable(): BarDrawable?

        open fun scrollUp() {
            getRecyclerView()?.scrollToPosition(0)
        }

        override fun onResume() {
            super.onResume()
            attachScrollHandler()
        }

        override fun onPause() {
            super.onPause()
            detachScrollHandler()
        }

        override fun onHiddenChanged(hidden: Boolean) {
            super.onHiddenChanged(hidden)
            if (hidden) {
                detachScrollHandler()
            } else {
                attachScrollHandler()
            }
        }

        private fun attachScrollHandler() {
            getRecyclerView()?.let {
                scrollListener.attach(it)
            }
        }

        private fun detachScrollHandler() {
            scrollListener.detach()
        }
    }

    override val viewModel: MainViewModel by viewModel()
    private val rootViewModel: RootViewModel by activityViewModel()

    private val fragments: MutableMap<Int, Fragment> = mutableMapOf()

    private lateinit var bottomTabsView: BottomTabsView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.removeAllFragments()

        bottomTabsView = view.findViewById(R.id.bottom_tabs)
        if (requireContext().isLightTheme) {
            bottomTabsView.setBgColor(requireContext().backgroundPageColor)
        } else {
            bottomTabsView.setBgColor(requireContext().backgroundTransparentColor)
        }
        bottomTabsView.doOnLongClick = { itemId ->
            if (itemId == R.id.wallet) {
                navigation?.add(PickerScreen.newInstance(from = getCurrentFrom()))
            }
        }
        collectFlow(viewModel.childBottomScrolled) {
            bottomTabsView.setDivider(it)
        }
        rootViewModel.eventFlow.filterIsInstance<RootEvent.OpenTab>().onEach {
            val itemId = R.id.wallet
            bottomTabsView.selectedItemId = itemId
            setFragment(itemId, it.wallet, it.from, null, true)
            parentClearState()
        }.launchIn(lifecycleScope)
        collectFlow(viewModel.selectedWalletFlow) { wallet ->
            applyWallet(wallet)
            setFragment(bottomTabsView.selectedItemId, wallet, "wallet",null, false)
        }
    }

    override fun onBackPressed(): Boolean {
        val visibleFragment = childFragmentManager.fragments.find {
            !it.isHidden && !it.isDetached
        } as BaseFragment
        return if (visibleFragment is BrowserBaseScreen) {
            visibleFragment.onBackPressed()
        } else {
            super.onBackPressed()
        }
    }

    private fun parentClearState() {
        val activity = context?.activity ?: return
        val view = activity.findViewById<View>(uikit.R.id.root_container)
        view.roundTop(0)
        view.scale = 1f
        view.alpha = 1f
    }

    private fun applyWallet(wallet: WalletEntity) {
        if (fragments.isNotEmpty()) {
            childFragmentManager.removeAllFragments()
            fragments.clear()
        }

        bottomTabsView.doOnClick = { itemId ->
            setFragment(itemId, wallet, "wallet",null, false)
        }
    }

    private fun getCurrentFrom(): String {
        return "wallet"
    }

    private fun getFragment(itemId: Int, wallet: WalletEntity): Fragment {
        return fragments[itemId] ?: createFragment(itemId, wallet).also {
            fragments[itemId] = it
        }
    }

    private fun createFragment(itemId: Int, wallet: WalletEntity): Fragment {
        require(itemId == R.id.wallet) { "Unknown itemId: $itemId" }
        return WalletScreen.newInstance(wallet)
    }

    private fun setFragment(itemId: Int, wallet: WalletEntity, from: String, extra: String?, forceScrollUp: Boolean) {
        viewModel.setData(wallet, itemId)
        setFragment(getFragment(itemId, wallet), forceScrollUp, from, extra, 0)
    }

    private fun setFragment(fragment: Fragment, forceScrollUp: Boolean, from: String, extra: String?, attempt: Int) {
        if (attempt > 3) {
            throw IllegalStateException("Failed to set main fragment")
        }

        if (childFragmentManager.isStateSaved) {
            return
        }

        if (fragment.isAdded && !fragment.isHidden) {
            (fragment as? Child)?.scrollUp()
            return
        }
        val transaction = childFragmentManager.beginTransaction()
        childFragmentManager.fragments.filter {
            it != fragment && !it.isHidden
        }.forEach { transaction.hide(it) }

        if (fragment.isAdded) {
            transaction.show(fragment)
            if (forceScrollUp) {
                (fragment as? Child)?.scrollUp()
            }
        } else {
            transaction.add(R.id.child_fragment, fragment)
        }
        transaction.runOnCommit {
            checkBottomDivider(fragment)
            if (fragment is BrowserBaseScreen) {
                analytics?.simpleTrackScreenEvent("browser_open", from)
                if (!extra.isNullOrBlank()) {
                    fragment.openCategory(extra)
                }
            } else if (fragment is EventsScreen) {
                analytics?.simpleTrackScreenEvent("history_open", from)
            } else if (fragment is CollectiblesScreen) {
                analytics?.simpleTrackScreenEvent("collectibles_open", from)
            } else if (fragment is WalletScreen) {
                analytics?.simpleTrackEvent("wallet_open", hashMapOf(
                    "from" to from,
                    "wallet_type" to fragment.wallet.version.title
                ))
            }
        }
        try {
            transaction.commitNow()
            Log.d("MainScreenLog", "Set fragment: $fragment")
        } catch (e: Throwable) {
            Log.e("MainScreenLog", "Failed to set fragment", e)
            CrashReporter.recordException(e)
            postDelayed(1000) {
                setFragment(fragment, forceScrollUp, from,extra, attempt + 1)
            }
        }
    }

    private fun checkBottomDivider(fragment: Fragment) {
        if (fragment is BrowserBaseScreen) {
            bottomTabsView.setDivider(false)
        }
    }

    override fun onResume() {
        super.onResume()
        window?.setBackgroundDrawable(requireContext().constantBlackColor.drawable)
    }

    companion object {

        fun newInstance() = MainScreen()
    }

}
