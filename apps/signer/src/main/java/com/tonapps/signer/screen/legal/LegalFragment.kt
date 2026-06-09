package com.tonapps.signer.screen.legal

import android.os.Bundle
import android.view.View
import com.tonapps.signer.R
import uikit.base.BaseFragment
import uikit.navigation.Navigation.Companion.navigation
import uikit.widget.HeaderView

class LegalFragment: BaseFragment(R.layout.fragment_legal), BaseFragment.SwipeBack {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val header = view.findViewById<HeaderView>(R.id.header)
        header.doOnCloseClick = { finish() }

        val termsView = view.findViewById<View>(R.id.terms)
        termsView.setOnClickListener {
            navigation?.openURL("https://tos.network/terms")
        }

        val privacyView = view.findViewById<View>(R.id.privacy)
        privacyView.setOnClickListener {
            navigation?.openURL("https://tos.network/privacy")
        }
    }

    companion object {
        fun newInstance() = LegalFragment()
    }
}