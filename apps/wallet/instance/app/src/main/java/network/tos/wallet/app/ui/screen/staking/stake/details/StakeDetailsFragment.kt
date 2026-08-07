package network.tos.wallet.app.ui.screen.staking.stake.details

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.net.toUri
import com.google.android.flexbox.FlexboxLayout
import network.tos.extensions.getParcelableCompat
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.koin.api
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingViewModel
import network.tos.wallet.app.ui.screen.staking.stake.amount.StakeAmountFragment
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeScreen
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeViewModel
import network.tos.wallet.app.ui.screen.swap.SwapArgs
import network.tos.wallet.app.R
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolDetailsEntity
import network.tos.wallet.data.staking.entities.PoolEntity
import network.tos.wallet.data.staking.entities.PoolInfoEntity
import network.tos.wallet.localization.Localization
import uikit.extensions.applyBottomInsets
import uikit.extensions.dp
import uikit.extensions.drawable
import uikit.extensions.getSpannable
import uikit.extensions.inflate
import uikit.extensions.pinToBottomInsets
import uikit.extensions.setLeftDrawable
import uikit.extensions.withGreenBadge
import uikit.navigation.Navigation.Companion.navigation
import uikit.widget.HeaderView

class StakeDetailsFragment :
    BaseHolderWalletScreen.ChildFragment<StakingScreen, StakingViewModel>(R.layout.fragment_stake_details) {

    private val args: StakeDetailsArgs by lazy { StakeDetailsArgs(requireArguments()) }

    private lateinit var detailsContentView: View
    private lateinit var poolApyTitleView: AppCompatTextView
    private lateinit var descriptionView: AppCompatTextView
    private lateinit var linkDrawable: Drawable
    private lateinit var linksView: FlexboxLayout
    private lateinit var button: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val headerView = view.findViewById<HeaderView>(R.id.header)
        headerView.doOnCloseClick = { popBackStack() }
        headerView.doOnActionClick = { finish() }
        headerView.title = args.name

        detailsContentView = view.findViewById(R.id.details_content)

        poolApyTitleView = view.findViewById(R.id.pool_apy_title)

        descriptionView = view.findViewById(R.id.staking_description)

        linkDrawable = requireContext().drawable(UIKitIcon.ic_globe_16)

        detailsContentView.visibility = View.VISIBLE
        if (args.maxApy) {
            poolApyTitleView.text = getString(Localization.staking_apy).withGreenBadge(
                requireContext(),
                Localization.staking_max_apy
            )
        } else {
            poolApyTitleView.text = getString(Localization.staking_apy)
        }
        val apyView = view.findViewById<AppCompatTextView>(R.id.pool_apy)
        apyView.text = "≈ ${CurrencyFormatter.formatPercent(args.apy)}"

        val minDepositView = view.findViewById<AppCompatTextView>(R.id.pool_min_deposit)
        minDepositView.text = CurrencyFormatter.format(TokenEntity.TON.symbol, args.minStake)

        linksView = view.findViewById(R.id.links)
        applyLinks(args.links)

        button = view.findViewById(R.id.choose_button)
        button.setOnClickListener {
            primaryViewModel.selectPool(args.pool)
            popBackStack(StakeAmountFragment.TAG)
        }
        button.applyBottomInsets()
    }

    private fun applyLinks(links: List<String>) {
        linksView.removeAllViews()
        for (link in links) {
            val host = Uri.parse(link).host!!
            val linkView =
                requireContext().inflate(R.layout.view_link, linksView) as AppCompatTextView
            linkView.text = host
            linkView.setLeftDrawable(linkDrawable)
            linkView.setOnClickListener { BrowserHelper.open(requireContext(), link) }
            linksView.addView(linkView)
        }
    }

    override fun onKeyboardAnimation(offset: Int, progress: Float, isShowing: Boolean) {
        super.onKeyboardAnimation(offset, progress, isShowing)
        button.translationY = -offset.toFloat()
    }

    companion object {
        fun newInstance(
            info: PoolInfoEntity,
            poolAddress: String
        ): StakeDetailsFragment {
            val fragment = StakeDetailsFragment()
            fragment.setArgs(StakeDetailsArgs(info, poolAddress))
            return fragment
        }
    }
}