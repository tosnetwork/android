package network.tos.wallet.app.ui.screen.migration

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.base.BaseWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.R
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class MigrationScreen: BaseWalletScreen<ScreenContext.None>(R.layout.fragment_migration, ScreenContext.None), BaseFragment.SwipeBack {

    override val fragmentName: String = "MigrationScreen"

    override val viewModel: MigrationViewModel by viewModel()

    private lateinit var legacyStateView: AppCompatTextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        legacyStateView = view.findViewById(R.id.legacy_state)
        collectFlow(viewModel.legacyStateFlow, ::applyLegacyState)
    }

    private fun applyLegacyState(state: MigrationViewModel.LegacyState) {
        val lines = mutableListOf<String>()
        lines.add("WalletsCount: ${state.walletsCount}")
        lines.add("Lockscreen: ${state.lockScreenEnabled}")
        lines.add("Biometrics: ${state.biometryEnabled}")
        lines.add("SelectedId: ${state.selectedIdentifier}")
        legacyStateView.text = lines.joinToString("\n")
    }

    companion object {

        fun newInstance() = MigrationScreen()
    }
}