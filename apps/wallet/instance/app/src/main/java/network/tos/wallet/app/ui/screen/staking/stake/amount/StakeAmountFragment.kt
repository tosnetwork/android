package network.tos.wallet.app.ui.screen.staking.stake.amount

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.koin.analytics
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.component.coin.CoinEditText
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingViewModel
import network.tos.wallet.app.ui.screen.staking.stake.confirm.StakeConfirmFragment
import network.tos.wallet.app.ui.screen.staking.stake.options.StakeOptionsFragment
import network.tos.wallet.app.R
import network.tos.uikit.color.accentGreenColor
import network.tos.uikit.color.accentRedColor
import network.tos.uikit.color.stateList
import network.tos.uikit.color.textSecondaryColor
import network.tos.wallet.data.core.HIDDEN_BALANCE
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.map
import uikit.extensions.collectFlow
import uikit.extensions.focusWithKeyboard
import uikit.extensions.hideKeyboard
import uikit.extensions.withAlpha
import uikit.widget.AsyncImageView
import uikit.widget.HeaderView
import kotlin.collections.plus

class StakeAmountFragment :
    BaseHolderWalletScreen.ChildFragment<StakingScreen, StakingViewModel>(R.layout.fragment_stake_amount) {

    private val from: String by lazy { arguments?.getString(ARG_FROM) ?: "" }

    private lateinit var amountView: CoinEditText
    private lateinit var poolItemView: View
    private lateinit var poolIconView: AsyncImageView
    private lateinit var poolTitleView: AppCompatTextView
    private lateinit var poolMaxApyView: View
    private lateinit var poolDescriptionView: AppCompatTextView
    private lateinit var availableView: AppCompatTextView
    private lateinit var currencyView: AppCompatTextView
    private lateinit var button: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val headerView = view.findViewById<HeaderView>(R.id.header)
        headerView.doOnCloseClick = {
            serverConfig?.stakingInfoUrl?.let {
                navigation?.openURL(it)
            }
        }
        headerView.doOnActionClick = { finish() }

        amountView = view.findViewById(R.id.stake_amount)
        amountView.doOnValueChange = { value, _ -> primaryViewModel.updateAmount(value) }

        currencyView = view.findViewById(R.id.stake_currency)

        poolItemView = view.findViewById(R.id.pool_item)
        poolItemView.setOnClickListener {
            setFragment(
                StakeOptionsFragment.newInstance(
                    primaryFragment.screenContext.wallet
                )
            )
        }

        poolIconView = view.findViewById(R.id.pool_icon)
        poolIconView.setCircular()

        poolTitleView = view.findViewById(R.id.pool_name)
        poolMaxApyView = view.findViewById(R.id.pool_max_apy)
        poolMaxApyView.backgroundTintList =
            requireContext().accentGreenColor.withAlpha(.16f).stateList

        poolDescriptionView = view.findViewById(R.id.pool_description)

        availableView = view.findViewById(R.id.available)

        button = view.findViewById(R.id.next_button)

        button.setOnClickListener {
            openConfirm()
        }

        view.findViewById<View>(R.id.max).setOnClickListener { applyMax() }

        collectFlow(primaryViewModel.selectedPoolFlow, ::applyPoolInfo)
        collectFlow(primaryViewModel.availableUiStateFlow, ::applyAvailableState)
        collectFlow(primaryViewModel.fiatFormatFlow, ::applyFiat)
        collectFlow(primaryViewModel.apyFormatFlow.map {
            it.withCustomSymbol(requireContext())
        }, poolDescriptionView::setText)

        collectFlow(primaryViewModel.tokenFlow) { token ->
            amountView.suffix = token.symbol
        }

        collectFlow(primaryViewModel.analyticsFlow) { props ->
            context?.analytics?.simpleTrackEvent(
                "staking_plus_input", props.plus("from" to from) as MutableMap<String, Any>
            )
        }
    }

    private fun applyMax() {
        collectFlow(primaryViewModel.requestMax()) {
            amountView.setValue(it.value)
        }
    }

    override fun onKeyboardAnimation(offset: Int, progress: Float, isShowing: Boolean) {
        super.onKeyboardAnimation(offset, progress, isShowing)
        button.translationY = -offset.toFloat()
    }

    override fun onVisibleState(visible: Boolean) {
        super.onVisibleState(visible)
        if (visible) {
            amountView.focusWithKeyboard()
        } else {
            amountView.hideKeyboard()
        }
    }

    override fun toString() = TAG

    private fun applyFiat(fiatFormat: CharSequence) {
        currencyView.text = fiatFormat.withCustomSymbol(requireContext())
    }

    private fun applyAvailableState(state: StakingViewModel.AvailableUiState) {
        button.text = getString(Localization.continue_action)
        if (state.insufficientBalance) {
            availableView.setText(Localization.insufficient_balance)
            availableView.setTextColor(requireContext().accentRedColor)
            button.isEnabled = false
        } else if (state.remainingFormat == state.balanceFormat) {
            availableView.text = if (state.hiddenBalance) HIDDEN_BALANCE else getString(
                Localization.available_balance,
                state.balanceFormat
            ).withCustomSymbol(requireContext())
            availableView.setTextColor(requireContext().textSecondaryColor)
            button.isEnabled = false
        } else if (state.requestMinStake) {
            availableView.text =
                getString(Localization.minimum_amount, state.minStakeFormat).withCustomSymbol(
                    requireContext()
                )
            availableView.setTextColor(requireContext().accentRedColor)
            button.isEnabled = false
        } else {
            availableView.text =
                getString(Localization.remaining_balance, state.remainingFormat).withCustomSymbol(
                    requireContext()
                )
            availableView.setTextColor(requireContext().textSecondaryColor)
            button.isEnabled = true
        }
    }

    private fun applyPoolInfo(pool: PoolEntity) {
        poolIconView.setLocalRes(StakingPool.getIcon(pool.implementation))
        poolTitleView.text = pool.name.ifBlank {
            getString(StakingPool.getTitle(pool.implementation))
        }
        poolMaxApyView.visibility = if (pool.maxApy) View.VISIBLE else View.GONE
    }

    private fun openConfirm() {
        setFragment(StakeConfirmFragment.newInstance())
    }

    companion object {

        const val TAG = "stake_amount_fragment"

        private const val ARG_FROM = "from"

        fun newInstance(from: String): StakeAmountFragment {
            val fragment = StakeAmountFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_FROM, from)
            }
            return fragment
        }
    }
}