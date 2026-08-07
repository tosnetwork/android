package network.tos.signer.screen.notfound

import android.os.Bundle
import android.view.View
import android.widget.Button
import network.tos.signer.R
import uikit.base.BaseFragment
import uikit.widget.HeaderView

class NoFoundFragment: BaseFragment(R.layout.fragment_notfound), BaseFragment.Modal {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<HeaderView>(R.id.header).doOnActionClick = { finish() }
        view.findViewById<Button>(R.id.ok).setOnClickListener { finish() }
    }

    companion object {
        fun newInstance() = NoFoundFragment()
    }
}