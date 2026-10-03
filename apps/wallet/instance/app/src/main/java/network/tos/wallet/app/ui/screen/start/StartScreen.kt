package network.tos.wallet.app.ui.screen.start

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.tos.wallet.api.API
import org.koin.android.ext.android.inject
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
    private val api: API by inject()

    private fun showNodeDialog() {
        val input = AppCompatEditText(requireContext()).apply {
            setText(api.tosRpcEndpoint(false))
            hint = getString(R.string.rpc_node_address)
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(R.string.rpc_node)
            .setMessage(R.string.rpc_node_hint).setView(input)
            .setPositiveButton(R.string.rpc_node_save, null)
            .setNeutralButton(R.string.rpc_node_reset) { _, _ -> api.resetCustomTosRpcEndpoint() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { api.setCustomTosRpcEndpoint(input.text.toString()); dialog.dismiss() }
                catch (_: IllegalArgumentException) { input.error = getString(R.string.rpc_node_invalid) }
            }
        }
        dialog.show()
    }

    private fun openWhenNodeReady(action: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { runCatching { api.tos.getNetworkInfo(false).requireNativeV5() }.isSuccess }
            if (ready) action() else AlertDialog.Builder(requireContext())
                .setTitle(R.string.rpc_node_unavailable)
                .setMessage(R.string.rpc_node_connect_hint)
                .setPositiveButton(R.string.rpc_node_retry) { _, _ -> openWhenNodeReady(action) }
                .setNeutralButton(R.string.rpc_node) { _, _ -> showNodeDialog() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

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
            openWhenNodeReady { navigation?.add(InitScreen.newInstance(InitArgs.Type.New)) }
        }

        val importWalletButton = view.findViewById<Button>(R.id.import_wallet)
        importWalletButton.setOnClickListener {
            openWhenNodeReady { navigation?.add(AddWalletScreen.newInstance(false)) }
        }

        view.findViewById<View>(R.id.rpc_node).setOnClickListener { showNodeDialog() }

        view.findViewById<View>(R.id.terms).setOnClickListener {
            BrowserHelper.open(requireContext(), "https://tos.network/terms.html")
        }
    }

    companion object {
        fun newInstance() = StartScreen()
    }
}
