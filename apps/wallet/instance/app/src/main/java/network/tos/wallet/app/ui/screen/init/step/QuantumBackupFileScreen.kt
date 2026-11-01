package network.tos.wallet.app.ui.screen.init.step

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.ui.screen.init.InitViewModel
import network.tos.wallet.app.ui.screen.pq.QuantumNetworkDialog
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment
import uikit.extensions.collectFlow
import uikit.extensions.dp

/** Encrypted file recovery; no signing capability is inferred from backup contents. */
class QuantumBackupFileScreen : BaseFragment(0) {
    override val fragmentName = "QuantumBackupFileScreen"
    override val secure = !BuildConfig.DEBUG
    private val model: InitViewModel by viewModel(ownerProducer = { requireParentFragment() })
    private lateinit var password: AppCompatEditText
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var configure: Button
    private lateinit var review: Button
    private var encrypted: ByteArray? = null
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var generation = 0L
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || view == null) return@registerForActivityResult
        clear()
        val token = generation
        val resolver = requireContext().contentResolver
        busy = true; status.text = "Reading backup…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val input = runInterruptible(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    QuantumBackupDocument.read { requireNotNull(resolver.openInputStream(uri)) }
                }
                if (token != generation) return@launch
                encrypted = input; busy = false; job = null
                status.text = "Encrypted backup selected. Enter its password to review the original wallet."
                render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(token, "Could not read this file. Choose a TOS encrypted backup.") }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 80.dp, 24.dp, 24.dp)
        }
        fun text(value: String) = TextView(context).apply { text = value; textSize = 17f }
        fun button(value: String) = Button(context).apply { text = value; isAllCaps = false }
        content.addView(text("Encrypted TOS backup"))
        content.addView(text("Restore the original wallet address. Payments remain locked during recovery. Keep your original backup and password."))
        choose = button("Choose backup file").apply { contentDescription = "quantum.backup.choose" }
        configure = button("Configure trusted TOS network")
        password = AppCompatEditText(context).apply {
            hint = "Backup file password"; contentDescription = "quantum.backup.password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setSingleLine(true)
            doAfterTextChanged { render() }
        }
        review = button("Review backup").apply { contentDescription = "quantum.backup.review" }
        status = text("Choose your encrypted backup file.")
        listOf(choose, configure, password, review, status).forEach { content.addView(it) }
        choose.setOnClickListener { clear(); picker.launch(arrayOf("application/octet-stream", "application/json", "*/*")) }
        configure.setOnClickListener { clear(keepFile = true); QuantumNetworkDialog.show(context) }
        review.setOnClickListener { confirmNetwork() }
        return ScrollView(context).apply { isSaveEnabled = false; addView(content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectFlow(model.uiTopOffset) { top -> (view as ScrollView).getChildAt(0).setPadding(24.dp, top + 24.dp, 24.dp, 24.dp) }
        render()
    }

    private fun render() {
        if (!::review.isInitialized) return
        choose.isEnabled = !busy; configure.isEnabled = !busy; password.isEnabled = !busy
        val value = password.text
        review.isEnabled = !busy && encrypted != null && value != null &&
            Character.codePointCount(value, 0, value.length) >= 12 && value.length <= 1024 && !value.contains('\u0000')
    }

    private fun clear(keepFile: Boolean = false) {
        generation++; job?.cancel(); job = null; busy = false
        dialog?.dismiss(); dialog = null
        if (::password.isInitialized) password.text?.clear()
        if (!keepFile) { encrypted?.fill(0); encrypted = null }
        render()
    }

    override fun onStop() {
        clear()
        if (::status.isInitialized) status.text = "Choose your backup again to continue."
        super.onStop()
    }

    private fun confirmNetwork() {
        if (busy || !review.isEnabled) return
        val profile = try { QuantumNetworkProfile.effective(requireContext()) }
        catch (_: Exception) { status.text = "Configure a trusted TOS network first."; return }
        val token = generation
        dialog = AlertDialog.Builder(requireContext()).setTitle("Confirm Recovery Network")
            .setMessage("Use your independently trusted network. The file cannot change it.\n\nGlobal ID: ${profile.globalId}\nRoot: ${profile.rootHash}\nFile: ${profile.fileHash}")
            .setPositiveButton("Use This Network") { _, _ -> inspect(profile, token) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> password.text?.clear() }
            .setOnCancelListener { password.text?.clear() }.show()
    }

    private fun secret(): CharArray {
        val value = requireNotNull(password.text)
        return CharArray(value.length) { value[it] }
    }

    private fun inspect(profile: QuantumNetworkProfile, token: Long) {
        val input = encrypted?.copyOf() ?: return
        if (token != generation || busy) return
        val pass = secret()
        busy = true; status.text = "Verifying network and backup…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val namespace = model.backupRecoveryNamespace(profile)
                val preview = model.inspectBackupFile(input, pass, profile, namespace)
                if (token != generation) return@launch
                busy = false; job = null; render()
                val roles = listOfNotNull(if (preview.containsPrimary) "primary key" else null,
                    if (preview.containsRescue) "rescue key" else null).joinToString(", ")
                dialog = AlertDialog.Builder(requireContext()).setTitle("Review Recovery Wallet")
                    .setMessage("Original address:\n${preview.address}\n\nBackup contains: $roles.\n\nPayments remain locked during recovery.")
                    .setPositiveButton("Import Recovery Wallet") { _, _ -> importReviewed(profile, namespace, token) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> password.text?.clear() }
                    .setOnCancelListener { password.text?.clear() }.show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(token, "Could not verify this backup. Check its password, trusted network and TOS node.") }
            finally { pass.fill('\u0000'); input.fill(0) }
        }
    }

    private fun importReviewed(profile: QuantumNetworkProfile, namespace: ByteArray, token: Long) {
        val input = encrypted?.copyOf() ?: return
        if (token != generation || busy) return
        val pass = secret(); password.text?.clear()
        busy = true; status.text = "Importing recovery wallet…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try { model.importBackupFile(requireContext(), input, pass, profile, namespace) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(token, "Import could not finish. Keep the same backup and retry. Payments remain locked.") }
            finally { pass.fill('\u0000'); input.fill(0) }
        }
    }

    private fun fail(token: Long, message: String) {
        if (token != generation) return
        job = null; busy = false; password.text?.clear(); status.text = message; render()
    }
}
