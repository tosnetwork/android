package network.tos.wallet.app.ui.screen.staking.unstake.confirm

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import network.tos.extensions.short12
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.extensions.getTitle
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.screen.send.main.SendException
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeScreen
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeViewModel
import network.tos.wallet.app.ui.screen.staking.viewer.StakeViewerScreen
import network.tos.wallet.app.view.TransactionDetailView
import network.tos.wallet.app.R
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uikit.extensions.collectFlow
import uikit.widget.AsyncImageView
import uikit.widget.HeaderView
import uikit.widget.ProcessTaskView

class UnStakeConfirmFragment: BaseHolderWalletScreen.ChildFragment<UnStakeScreen, UnStakeViewModel>(R.layout.fragment_unstake_confirm) {

    private lateinit var iconView: AsyncImageView
    private lateinit var walletView: TransactionDetailView
    private lateinit var recipientView: TransactionDetailView
    private lateinit var amountView: TransactionDetailView
    private lateinit var feeView: TransactionDetailView
    private lateinit var button: Button
    private lateinit var taskView: ProcessTaskView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val headerView = view.findViewById<HeaderView>(R.id.header)
        headerView.doOnCloseClick = { popBackStack() }
        headerView.doOnActionClick = { finish() }

        iconView = view.findViewById(R.id.icon)
        iconView.setCircular()

        walletView = view.findViewById(R.id.review_wallet)
        recipientView = view.findViewById(R.id.review_recipient)
        amountView = view.findViewById(R.id.review_amount)

        feeView = view.findViewById(R.id.review_fee)
        feeView.setLoading()

        button = view.findViewById(R.id.button)
        taskView = view.findViewById(R.id.task)

        button.setOnClickListener { unStake() }

        walletView.value = primaryFragment.screenContext.wallet.label.getTitle(requireContext(), walletView.valueView, 12)

        collectFlow(primaryViewModel.poolFlow, ::applyPool)
        collectFlow(primaryViewModel.taskStateFlow, ::setTaskState)

        collectFlow(primaryViewModel.amountFormatFlow) { amountFormat ->
            amountView.value = amountFormat.withCustomSymbol(requireContext())
        }

        collectFlow(primaryViewModel.fiatFormatFlow) { fiatFormat ->
            amountView.description = fiatFormat.withCustomSymbol(requireContext())
        }

        collectFlow(primaryViewModel.requestFeeFormat()) { (feeFormat, feeFiatFormat) ->
            feeView.setDefault()
            feeView.value = "≈ " + feeFormat.withCustomSymbol(requireContext())
            feeView.description = "≈ " + feeFiatFormat.withCustomSymbol(requireContext())
            button.isEnabled = true
        }
    }

    override fun onKeyboardAnimation(offset: Int, progress: Float, isShowing: Boolean) {
        super.onKeyboardAnimation(offset, progress, isShowing)
        button.translationY = -offset.toFloat()
        taskView.translationY = -offset.toFloat()
    }

    private fun unStake() {
        setTaskState(ProcessTaskView.State.LOADING)
        primaryViewModel.unStake(requireContext()).catch { e ->
            val state = if (e is SendException.Cancelled) ProcessTaskView.State.DEFAULT else ProcessTaskView.State.FAILED
            setTaskState(state)
        }.onEach {
            setTaskState(ProcessTaskView.State.SUCCESS)
            navigation?.openURL("tos://activity?from=unstake")
            close()
        }.launchIn(lifecycleScope)
    }

    private fun close() {
        val runnable = Runnable {
            finish()
        }
        navigation?.removeByClass(runnable, StakeViewerScreen::class.java)
    }

    private fun applyPool(pool: PoolEntity) {
        iconView.setLocalRes(StakingPool.getIcon(pool.implementation))
        recipientView.value = pool.name.short12
    }

    private fun setTaskState(state: ProcessTaskView.State) {
        when(state) {
            ProcessTaskView.State.DEFAULT -> setDefaultState()
            ProcessTaskView.State.LOADING -> setLoadingState()
            ProcessTaskView.State.SUCCESS -> setSuccessState()
            ProcessTaskView.State.FAILED -> setFailedState()
        }
    }

    private fun setDefaultState() {
        taskView.visibility = View.GONE
        button.visibility = View.VISIBLE
    }

    private fun setLoadingState() {
        taskView.visibility = View.VISIBLE
        button.visibility = View.GONE
        taskView.state = ProcessTaskView.State.LOADING
    }

    private fun setFailedState() {
        taskView.state = ProcessTaskView.State.FAILED
        lifecycleScope.launch {
            delay(3000)
            setDefaultState()
        }
    }

    private fun setSuccessState() {
        taskView.state = ProcessTaskView.State.SUCCESS
    }

    override fun getTitle() = ""

    companion object {
        fun newInstance() = UnStakeConfirmFragment()
    }
}