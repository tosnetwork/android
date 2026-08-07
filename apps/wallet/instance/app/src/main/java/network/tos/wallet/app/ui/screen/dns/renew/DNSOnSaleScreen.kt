package network.tos.wallet.app.ui.screen.dns.renew

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.R
import uikit.base.BaseFragment
import uikit.extensions.applyNavBottomPadding
import uikit.extensions.getDimensionPixelSize

class DNSOnSaleScreen: BaseFragment(R.layout.dialog_dns_onsale), BaseFragment.Modal {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.close).setOnClickListener { finish() }
        view.findViewById<View>(R.id.ton).setOnClickListener { finish() }

        view.findViewById<View>(R.id.container)
            .applyNavBottomPadding(requireContext().getDimensionPixelSize(uikit.R.dimen.offsetMedium))
    }

    companion object {

        fun newInstance() = DNSOnSaleScreen()
    }
}