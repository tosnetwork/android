package network.tos.wallet.app.ui.screen.start

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val accounts: network.tos.wallet.data.account.AccountRepository by inject()
    private var quantumCreationPreflightJob: Job? = null

    private fun showNodeDialog() {
        val input = AppCompatEditText(requireContext()).apply {
            setText(api.tosRpcEndpoint(false))
            hint = getString(R.string.rpc_node_address)
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(R.string.rpc_node)
            .setMessage(R.string.rpc_node_hint).setView(network.tos.wallet.app.ui.screen.pq.QuantumNetworkDialog.nodeEditor(requireContext(), input))
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

    private fun openQuantumCreationWhenNodeReady(creationId: String?) {
        if (creationId != null) {
            // An unfinished creation already has a protected phrase. Let the
            // user reveal and confirm its backup while offline; the resume
            // operation performs a fresh proof before publishing the wallet.
            navigation?.add(InitScreen.newInstance(InitArgs.Type.New, quantumCreationId = creationId))
            return
        }
        if (quantumCreationPreflightJob?.isActive == true) return
        val context = context ?: return
        val progressContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(context).apply { isIndeterminate = true })
            addView(TextView(context).apply {
                text = getString(R.string.quantum_network_verifying)
                setPadding((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            })
        }
        val progressDialog = AlertDialog.Builder(context)
            .setView(progressContent)
            .setCancelable(false)
            .create()
        progressDialog.show()
        view?.findViewById<Button>(R.id.new_wallet)?.isEnabled = false

        quantumCreationPreflightJob = viewLifecycleOwner.lifecycleScope.launch {
            // Authenticate a fresh proof against the locally selected anchor
            // before asking the user to create and confirm a recovery backup.
            val ready = try {
                withContext(Dispatchers.IO) { accounts.preflightQuantumNetwork(creationId) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                if (progressDialog.isShowing) progressDialog.dismiss()
                quantumCreationPreflightJob = null
                view?.findViewById<Button>(R.id.new_wallet)?.isEnabled = true
            }
            if (!isAdded || view == null) return@launch
            if (ready) {
                navigation?.add(InitScreen.newInstance(InitArgs.Type.New, quantumCreationId = creationId))
            } else {
                AlertDialog.Builder(context)
                    .setTitle(R.string.rpc_node_unavailable)
                    .setMessage(R.string.rpc_node_connect_hint)
                    .setPositiveButton(R.string.rpc_node_retry) { _, _ -> openQuantumCreationWhenNodeReady(creationId) }
                    .setNeutralButton(R.string.rpc_node) { _, _ -> showNodeDialog() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
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
            network.tos.wallet.app.ui.screen.pq.QuantumPendingCreationDialog.open(requireContext(), viewLifecycleOwner, accounts) { id ->
                openQuantumCreationWhenNodeReady(id)
            }
        }

        val importWalletButton = view.findViewById<Button>(R.id.import_wallet)
        importWalletButton.setOnClickListener {
            // Opening recovery must not depend on the selected RPC. Each chosen
            // import path performs its own network/proof checks before saving.
            navigation?.add(AddWalletScreen.newInstance(false))
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
