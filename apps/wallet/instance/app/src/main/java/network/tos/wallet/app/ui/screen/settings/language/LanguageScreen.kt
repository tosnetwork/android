package network.tos.wallet.app.ui.screen.settings.language

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.settings.language.list.Adapter
import network.tos.wallet.localization.Localization
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment
import uikit.base.BaseListFragment
import uikit.extensions.collectFlow

class LanguageScreen: BaseListWalletScreen<ScreenContext.None>(ScreenContext.None), BaseFragment.SwipeBack {

    override val fragmentName: String = "LanguageScreen"

    override val viewModel: LanguageViewModel by viewModel()

    private val adapter = Adapter {
        viewModel.setLanguage(it.code)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(Localization.language))
        setAdapter(adapter)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    companion object {
        fun newInstance() = LanguageScreen()
    }
}