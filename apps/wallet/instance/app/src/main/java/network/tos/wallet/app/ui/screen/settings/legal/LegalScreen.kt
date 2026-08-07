package network.tos.wallet.app.ui.screen.settings.legal

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.koin.serverConfig
import network.tos.wallet.app.R
import uikit.base.BaseFragment
import uikit.widget.HeaderView

class LegalScreen: BaseFragment(R.layout.fragment_legal), BaseFragment.SwipeBack {

    override val fragmentName: String = "LegalScreen"

    private lateinit var headerView: HeaderView
    private lateinit var termsView: View
    private lateinit var privacyView: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerView = view.findViewById(R.id.header)
        headerView.doOnCloseClick = { finish() }

        val serverConfig = requireContext().serverConfig!!

        termsView = view.findViewById(R.id.terms)
        termsView.setOnClickListener {
            BrowserHelper.open(requireActivity(), serverConfig.termsOfUseUrl)
        }

        privacyView = view.findViewById(R.id.privacy)
        privacyView.setOnClickListener {
            BrowserHelper.open(requireActivity(), serverConfig.privacyPolicyUrl)
        }
    }

    companion object {
        fun newInstance() = LegalScreen()
    }
}