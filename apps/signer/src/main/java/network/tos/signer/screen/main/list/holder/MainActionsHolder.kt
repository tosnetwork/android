package network.tos.signer.screen.main.list.holder

import android.view.View
import android.view.ViewGroup
import network.tos.signer.R
import network.tos.signer.screen.add.AddFragment
import network.tos.signer.screen.camera.CameraFragment
import network.tos.signer.screen.main.list.MainItem
import network.tos.signer.screen.settings.SettingsFragment
import uikit.navigation.Navigation

class MainActionsHolder(
    parent: ViewGroup
): MainHolder<MainItem.Actions>(parent, R.layout.view_main_actions) {

    private val scanView = findViewById<View>(R.id.scan)
    private val addView = findViewById<View>(R.id.add)
    private val settingsView = findViewById<View>(R.id.settings)

    init {
        scanView.setOnClickListener { Navigation.from(context)?.add(CameraFragment.newInstance()) }
        addView.setOnClickListener { Navigation.from(context)?.add(AddFragment.newInstance()) }
        settingsView.setOnClickListener { Navigation.from(context)?.add(SettingsFragment.newInstance()) }
    }

    override fun onBind(item: MainItem.Actions) {

    }

}