package network.tos.wallet.app.ui.screen.dns.renew

import android.os.Bundle
import android.view.View
import android.widget.Button
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.extensions.locale
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.dns.renew.list.Adapter
import network.tos.wallet.app.ui.screen.dns.renew.list.Item
import network.tos.wallet.app.ui.screen.root.RootViewModel
import network.tos.wallet.app.R
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.localization.Localization
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.collectFlow
import uikit.extensions.dp
import uikit.extensions.pinToBottomInsets

class DNSRenewScreen(wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.BottomSheet {

    private val rootViewModel: RootViewModel by activityViewModel()

    override val viewModel: DNSRenewViewModel by walletViewModel {
        parametersOf(requireArguments().getParcelableArrayList<DnsExpiringEntity>(ARG_ITEMS)!!)
    }

    private val adapter = Adapter()

    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, ::submitList)
    }

    private fun submitList(items: List<Item>) {
        adapter.submitList(items)
        if (items.isEmpty()) {
            finish()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(Localization.renew_dns_title))
        setAdapter(adapter)
        setNestedScrollingEnabled(true)

        actionButton = view.findViewById<Button>(R.id.action)

        collectFlow(viewModel.showRenewAllButtonFlow, ::applyRenewAllButtonState)
    }

    private fun applyRenewAllButtonState(show: Boolean) {
        if (show) {
            setBottomMargin(72.dp)
            actionButton.visibility = View.VISIBLE
            actionButton.text = requireContext().getString(Localization.renew_dns_until, DateHelper.untilDate(
                locale = requireContext().locale
            ))
            actionButton.pinToBottomInsets()
            actionButton.setOnClickListener { renewAll() }
        } else {
            actionButton.visibility = View.GONE
            setBottomMargin(0)
        }
    }

    private fun renewAll() {
        viewModel.renewAll {
            finish()
        }
    }

    companion object {

        private const val ARG_ITEMS = "items"

        fun newInstance(wallet: WalletEntity, items: List<DnsExpiringEntity>): DNSRenewScreen {
            val screen = DNSRenewScreen(wallet)
            screen.putParcelableListArg(ARG_ITEMS, items)
            return screen
        }
    }
}