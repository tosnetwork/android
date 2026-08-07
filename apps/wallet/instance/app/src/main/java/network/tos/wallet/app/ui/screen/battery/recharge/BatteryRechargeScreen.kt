package network.tos.wallet.app.ui.screen.battery.recharge

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.inflate
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import network.tos.extensions.bestMessage
import network.tos.extensions.getParcelableCompat
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.extensions.hideKeyboard
import network.tos.wallet.app.extensions.showToast
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.battery.recharge.entity.BatteryRechargeEvent
import network.tos.wallet.app.ui.screen.battery.recharge.list.Adapter
import network.tos.wallet.app.ui.screen.send.contacts.main.SendContactsScreen
import network.tos.wallet.app.ui.screen.send.main.SendContact
import network.tos.wallet.app.ui.screen.token.picker.TokenPickerScreen
import network.tos.wallet.app.R
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.entity.SignRequestEntity
import network.tos.wallet.data.token.entities.AccountTokenEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.circle
import uikit.extensions.collectFlow
import uikit.extensions.setPaddingBottom
import uikit.widget.AsyncImageView
import uikit.widget.InputView
import java.util.UUID

class BatteryRechargeScreen(wallet: WalletEntity) :
    BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)),
    BaseFragment.BottomSheet {

    override val fragmentName: String = "BatteryRechargeScreen"

    override val hasApplyWindowInsets: Boolean = false

    private val args: RechargeArgs by lazy { RechargeArgs(requireArguments()) }
    private val contractsRequestKey: String by lazy { "contacts_${UUID.randomUUID()}" }
    private val tokenRequestKey: String by lazy { "token_${UUID.randomUUID()}" }

    override val viewModel: BatteryRechargeViewModel by walletViewModel {
        parametersOf(args)
    }

    private val adapter = Adapter(
        onAddressChange = { viewModel.updateAddress(it) },
        openAddressBook = ::openAddressBook,
        onAmountChange = { viewModel.updateAmount(it) },
        onPackSelect = {
            hideKeyboard()
            viewModel.setSelectedPack(it)
        },
        onCustomAmountSelect = { viewModel.onCustomAmountSelect() },
        onContinue = ::onContinue,
        onSubmitPromo = {
            hideKeyboard()
            viewModel.applyPromo(it)
        }
    )

    private lateinit var listContainer: View
    private lateinit var tokenIconView: AsyncImageView
    private lateinit var tokenTitleView: AppCompatTextView

    private val addressInput: InputView?
        get() = findListItemView(0)?.findViewById(R.id.address)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)

        navigation?.setFragmentResultListener(contractsRequestKey) { bundle ->
            bundle.getParcelableCompat<SendContact>("contact")?.let {
                addressInput?.text = it.address
            }
        }

        navigation?.setFragmentResultListener(tokenRequestKey) { bundle ->
            bundle.getParcelableCompat<TokenEntity>(TokenPickerScreen.TOKEN)?.let {
                viewModel.setToken(it)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(uikit.R.id.list_container)

        setAdapter(adapter)

        val rightContentView = inflate(context, R.layout.view_battery_recharge_token, null)
        tokenIconView = rightContentView.findViewById(R.id.token_icon)
        tokenIconView.setCircular()
        tokenTitleView = rightContentView.findViewById(R.id.token_title)
        rightContentView.findViewById<LinearLayoutCompat>(R.id.token)
            .setOnClickListener { openTokenSelector() }

        headerView.hideCloseIcon()
        headerView.setRightContent(rightContentView)
        headerView.setAction(UIKitIcon.ic_close_16)
        headerView.doOnActionClick = { finish() }
        headerView.setTitleGravity(Gravity.START)
        headerView.title = when (args.isGift) {
            true -> getString(Localization.battery_gift_title)
            false -> getString(Localization.battery_recharge_title)
        }


        ViewCompat.setOnApplyWindowInsetsListener(listContainer) { _, insets ->
            val offset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            updateContainerOffset(offset)
            insets
        }

        collectFlow(viewModel.tokenFlow) { token ->
            tokenTitleView.text = token.symbol
            tokenIconView.setImageURI(token.imageUri, this)
        }
        collectFlow(viewModel.eventFlow, ::onEvent)
    }

    // Dirty hack because of bad design
    private fun updateContainerOffset(offset: Int) {
        listContainer.setPaddingBottom(offset)
    }

    override fun finish() {
        hideKeyboard()
        super.finish()
    }

    override fun onDragging() {
        super.onDragging()
        hideKeyboard()
    }

    private fun onContinue() {
        hideKeyboard()
        viewModel.onContinue()
    }

    private fun openAddressBook() {
        navigation?.add(SendContactsScreen.newInstance(screenContext.wallet, contractsRequestKey))
        hideKeyboard()
    }

    private fun openTokenSelector() {
        combine(
            viewModel.supportedTokensFlow.take(1),
            viewModel.tokenFlow.take(1)
        ) { allowedTokens, selectedToken ->
            navigation?.add(
                TokenPickerScreen.newInstance(
                wallet = screenContext.wallet,
                requestKey = tokenRequestKey,
                selectedToken = selectedToken.balance.token,
                allowedTokens = allowedTokens.map { it.address }
            ))
        }.launchIn(lifecycleScope)
    }

    private fun showError(message: String? = null) {
        navigation?.toast(message ?: getString(Localization.sending_error))
    }

    private fun onSuccess() {
        requireContext().showToast(Localization.battery_please_wait)
        navigation?.openURL("tos://activity?from=battery")
        navigation?.removeByClass({
            finish()
        }, BatteryScreen::class.java)
    }

    private fun sign(request: SignRequestEntity, forceRelayer: Boolean) {
        viewModel.sign(request, forceRelayer).catch {
            showError(it.bestMessage)
        }.onEach {
            postDelayed(1000) {
                onSuccess()
            }
        }.launchIn(lifecycleScope)
    }

    private fun onEvent(event: BatteryRechargeEvent) {
        when (event) {
            is BatteryRechargeEvent.Sign -> sign(event.request, event.forceRelayer)
            is BatteryRechargeEvent.Error -> showError()
            is BatteryRechargeEvent.MaxAmountError -> {
                val message = requireContext().getString(
                    Localization.battery_max_input_amount,
                    CurrencyFormatter.format(currency = event.currency, value = event.maxAmount)
                )
                showError(message)
            }
        }
    }

    companion object {

        fun newInstance(
            wallet: WalletEntity,
            token: AccountTokenEntity? = null,
            isGift: Boolean = false
        ): BatteryRechargeScreen {
            val fragment = BatteryRechargeScreen(wallet)
            fragment.setArgs(RechargeArgs(token, isGift))
            return fragment
        }
    }
}