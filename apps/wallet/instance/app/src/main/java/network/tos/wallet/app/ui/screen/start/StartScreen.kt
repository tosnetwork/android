package network.tos.wallet.app.ui.screen.start

import android.os.Bundle
import android.view.View
import android.widget.Button
import network.tos.wallet.app.ui.screen.add.AddWalletScreen
import network.tos.wallet.app.ui.screen.dev.DevScreen
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.ui.screen.init.InitArgs
import network.tos.wallet.app.ui.screen.init.InitScreen
import network.tos.wallet.app.R
import uikit.base.BaseFragment
import uikit.extensions.applyNavBottomPadding
import uikit.navigation.Navigation.Companion.navigation

class StartScreen: BaseFragment(R.layout.fragment_intro) {

    override val fragmentName: String = "StartScreen"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyNavBottomPadding()
        
        // The developer screen can export/display mnemonics and passcodes in clear text.
        // It must never be reachable in a production build.
        if (BuildConfig.DEBUG) {
            view.findViewById<View>(R.id.logo).setOnLongClickListener {
                navigation?.add(DevScreen.newInstance())
                true
            }
        }

        val newWalletButton = view.findViewById<Button>(R.id.new_wallet)
        newWalletButton.setOnClickListener {
            navigation?.add(InitScreen.newInstance(InitArgs.Type.New))
        }

        val importWalletButton = view.findViewById<Button>(R.id.import_wallet)
        importWalletButton.setOnClickListener {
            navigation?.add(AddWalletScreen.newInstance(false))
        }

        view.findViewById<View>(R.id.terms).setOnClickListener {
            BrowserHelper.open(requireContext(), "https://tos.network/terms.html")
        }
    }

    companion object {
        fun newInstance() = StartScreen()
    }
}
