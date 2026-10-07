package network.tos.wallet.app.ui.screen.pq

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.tos.wallet.app.R
import network.tos.wallet.data.account.pq.QuantumReviewCandidate
import network.tos.wallet.data.account.pq.QuantumWalletRepository
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumSeedVault
import org.koin.android.ext.android.inject
import org.ton.block.AddrStd
import uikit.base.BaseFragment
import uikit.navigation.NavigationActivity

/** Initial R2 account access. Current chain authority and recovery funding are separate workflow stages. */
class QuantumWalletsScreen : BaseFragment(R.layout.fragment_pq_wallets), BaseFragment.SwipeBack {
    override val fragmentName = "QuantumWalletsScreen"
    private val passcodes: PasscodeManager by inject()
    private val repository by lazy { QuantumReviewCandidate.repository(requireContext()) {
        withContext(Dispatchers.Main) { passcodes.confirmation(requireActivity() as NavigationActivity, "Quantum wallet") }
    } }
    private lateinit var layout: LinearLayout
    private var busy = false
    private var previouslySecure = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState); layout = view.findViewById(R.id.pq_content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        refresh()
    }
    override fun onResume() {
        super.onResume(); previouslySecure = requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onPause() {
        if (!previouslySecure) requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }
    private fun label(text: String) { layout.addView(TextView(requireContext()).apply { this.text = text; textSize = 17f; setPadding(0, 12, 0, 12) }) }
    private fun button(text: String, id: String, action: () -> Unit) { layout.addView(Button(requireContext()).apply {
        this.text = text; isAllCaps = false; contentDescription = id; setOnClickListener { if (!busy) action() }
    }) }
    private fun refresh() {
        layout.removeAllViews(); label("Quantum accounts")
        label("Development candidate. Network verification and recovery funding are pending. No account is marked ready from an import.")
        button("Import public recovery manifest", "quantum.import") { importManifest() }
        try { for (record in repository.list()) {
            label("${record.name}\n${record.address}")
            button("Restore PRIMARY access", "quantum.primary.${record.id}") { restore(record, QuantumRole.PRIMARY) }
            button("Restore SLH access", "quantum.rescue.${record.id}") { restore(record, QuantumRole.RESCUE) }
        }} catch (_: Exception) { unavailable() }
    }
    private fun run(onFinished: () -> Unit = {}, action: suspend () -> Unit) {
        busy = true
        val job = lifecycleScope.launch {
            try { action(); refresh() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { unavailable() } finally { busy = false }
        }
        job.invokeOnCompletion { onFinished() }
    }
    private fun unavailable() { if (isAdded) AlertDialog.Builder(requireContext()).setTitle("R2 operation unavailable")
        .setMessage("Check the wallet identity, candidate network, device unlock and recovery input.").setPositiveButton("OK", null).show() }
    private fun importManifest() {
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val name = EditText(requireContext()).apply { hint = "Account name" }
        val address = EditText(requireContext()).apply { hint = "Independently known wallet address" }
        val manifest = EditText(requireContext()).apply { hint = "Public recovery manifest"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        for (input in listOf(name, address, manifest)) body.addView(input)
        AlertDialog.Builder(requireContext()).setTitle("Import initial identity").setView(body).setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                val n = name.text.toString(); val a = address.text.toString(); val m = manifest.text.toString().toByteArray()
                for (input in listOf(name, address, manifest)) input.text.clear()
                run { withContext(Dispatchers.IO) { repository.registerInitial(n, m, AddrStd.parse(a)) } }
            }.show()
    }
    private fun restore(record: QuantumWalletRepository.Record, role: QuantumRole) {
        AlertDialog.Builder(requireContext()).setTitle("Recovery input profile")
            .setItems(arrayOf("Native TOS mnemonic", "32-byte raw master")) { _, choice ->
                restoreInput(record, role, choice == 0)
            }.show()
    }
    private fun restoreInput(record: QuantumWalletRepository.Record, role: QuantumRole, native: Boolean) {
        val input = EditText(requireContext()).apply {
            hint = if (native) "Native TOS mnemonic (12 or 24 words)" else "32-byte raw master (64 hex characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val password = EditText(requireContext()).apply { hint = "Exact mnemonic password (empty if unused)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; addView(input); if (native) addView(password) }
        val dialog = AlertDialog.Builder(requireContext()).setTitle("Restore ${role.name} access").setView(body)
            .setNegativeButton("Cancel") { _, _ -> input.text.clear(); password.text.clear() }.setPositiveButton("Restore") { _, _ ->
                val chars = CharArray(input.text.length) { input.text[it] }; input.text.clear()
                val pass = CharArray(password.text.length) { password.text[it] }; password.text.clear()
                if (native) {
                    busy = true
                    QuantumRecoveryInput.launchNativeConsumed(lifecycleScope, chars, pass, onFailure = { busy = false; unavailable() }) { consumed ->
                        try {
                            withContext(Dispatchers.IO) { repository.restoreInitialRole(record.id, record.address, role, consumed, QuantumSeedVault.MasterProfile.NATIVE_MNEMONIC) }
                            refresh()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { unavailable() } finally { busy = false }
                    }
                    return@setPositiveButton
                }
                pass.fill('\u0000')
                var master = ByteArray(0)
                try {
                    master = QuantumRecoveryInput.rawMasterAndWipe(chars)
                    val owned = master
                    busy = true
                    QuantumRecoveryInput.launchConsumed(lifecycleScope, owned) { consumed ->
                        try {
                            withContext(Dispatchers.IO) { repository.restoreInitialRole(record.id, record.address, role, consumed, QuantumSeedVault.MasterProfile.RAW_MASTER_32) }
                            refresh()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { unavailable() } finally { busy = false }
                    }
                } catch (_: Exception) { master.fill(0); unavailable() }
                finally { chars.fill('\u0000') }
            }.create()
        dialog.setOnCancelListener { input.text.clear(); password.text.clear() }; dialog.show()
    }
}
