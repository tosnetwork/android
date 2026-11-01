package network.tos.wallet.app.ui.screen.backup.main

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
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.QuantumBackupExportFile
import network.tos.uikit.color.backgroundPageColor
import network.tos.uikit.color.textPrimaryColor
import uikit.base.BaseFragment
import uikit.extensions.dp

class QuantumBackupExportScreen(wallet: WalletEntity) :
    BaseWalletScreen<ScreenContext.Wallet>(0, ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {
    override val fragmentName = "QuantumBackupExportScreen"
    override val secure = true
    override val viewModel: BackupViewModel by walletViewModel()
    private val files by lazy { QuantumBackupExportFile(requireContext().applicationContext) }
    private lateinit var password: AppCompatEditText
    private lateinit var confirmation: AppCompatEditText
    private lateinit var create: Button
    private lateinit var status: TextView
    private var operation: Job? = null
    private var ticket: QuantumBackupExportFile.Ticket? = null
    private var generation = 0L
    private var busy = false
    private var choosingDestination = false

    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        choosingDestination = false
        val staged = ticket
        if (view == null || uri == null || staged == null) {
            reset()
            if (::status.isInitialized) status.text = "Export cancelled. Your existing backups are unchanged."
            return@registerForActivityResult
        }
        val token = generation
        busy = true; status.text = "Saving and verifying encrypted backup…"; render()
        val resolver = requireContext().contentResolver
        operation = viewLifecycleOwner.lifecycleScope.launch {
            try {
                runInterruptible(Dispatchers.IO) { files.saveAndVerify(staged, resolver, uri) }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                viewModel.recordVerifiedEncryptedBackup()
                operation = null
                reset()
                status.text = "Encrypted backup saved and read back successfully. Keep its password separately and retain your previous backups. Restoring this file keeps payments locked until recovery checks complete."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure(token, "Could not verify completion. Keep your previous backups and any saved file. Create a new export to retry.") }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24.dp, 80.dp, 24.dp, 32.dp)
        }
        fun label(value: String) = TextView(context).apply { text = value; textSize = 17f; setTextColor(context.textPrimaryColor) }
        content.addView(Button(context).apply { text = "Cancel"; setOnClickListener { reset(); finish() } })
        content.addView(label("Export encrypted backup"))
        content.addView(label("Wallet: ${screenContext.wallet.label.name}\nOriginal address: ${screenContext.wallet.accountId}\nNetwork ID: ${screenContext.wallet.networkGlobalId}"))
        content.addView(label("This file includes the original master recovery phrase and both original keys, which can also control related accounts. It is not independent cold rescue storage. Keep previous verified backups after any key change."))
        content.addView(label("Choose a file password different from your App PIN, with at least 12 characters. Enter it twice exactly; losing this password can prevent file recovery."))
        fun field(hintText: String, identifier: String) = AppCompatEditText(context).apply {
            hint = hintText; contentDescription = identifier; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true); setTextColor(context.textPrimaryColor)
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints("newPassword")
            }
        }
        password = field("Backup file password", "quantum.backup.export.password")
        confirmation = field("Confirm file password", "quantum.backup.export.confirmation")
        create = Button(context).apply {
            text = "Create encrypted file"; isAllCaps = false
            contentDescription = "quantum.backup.export.create"
            setOnClickListener { prepareExport() }
        }
        status = label("Only a saved file that passes read-back verification is recorded as a completed backup.").apply {
            contentDescription = "quantum.backup.export.status"
        }
        listOf(password, confirmation, create, status).forEach(content::addView)
        password.doAfterTextChanged { render() }; confirmation.doAfterTextChanged { render() }
        render()
        return ScrollView(context).apply { setBackgroundColor(context.backgroundPageColor); addView(content) }
    }

    private fun render() {
        if (!::create.isInitialized) return
        password.isEnabled = !busy && !choosingDestination
        confirmation.isEnabled = password.isEnabled
        create.isEnabled = password.isEnabled && QuantumBackupExportPassword.matches(password.text, confirmation.text)
        create.alpha = if (create.isEnabled) 1f else .4f
    }
    private fun clearSecrets() {
        if (::password.isInitialized) password.text?.clear()
        if (::confirmation.isInitialized) confirmation.text?.clear()
    }
    private fun reset() {
        generation++; operation?.cancel(); operation = null; busy = false; choosingDestination = false
        clearSecrets()
        ticket?.let { runCatching { files.discard(it) } }; ticket = null
        render()
    }

    override fun onStop() {
        if (choosingDestination) clearSecrets()
        else if (busy || ticket != null || (::password.isInitialized && !password.text.isNullOrEmpty()) ||
                 (::confirmation.isInitialized && !confirmation.text.isNullOrEmpty())) {
            reset()
            if (::status.isInitialized) status.text = "Export interrupted. Keep any existing file and create a new export to verify completion."
        }
        super.onStop()
    }
    override fun onDestroyView() { reset(); super.onDestroyView() }

    private fun prepareExport() {
        if (busy || !create.isEnabled) return
        val text = checkNotNull(password.text)
        val pass = CharArray(text.length) { text[it] }
        val token = generation
        busy = true; clearSecrets(); status.text = "Authenticate to create this encrypted backup…"; render()
        operation = viewLifecycleOwner.lifecycleScope.launch {
            var prepared: QuantumBackupExportFile.Ticket? = null
            try {
                val encrypted = viewModel.createEncryptedBackup(requireContext(), pass)
                try { withContext(Dispatchers.IO) { prepared = files.stage(encrypted) } }
                finally { encrypted.fill(0) }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                ticket = checkNotNull(prepared); prepared = null
                operation = null; busy = false; choosingDestination = true; render()
                status.text = "Save a new encrypted file. Keep your previous backup until this file is saved and verified."
                destination.launch("TOS-backup-${System.currentTimeMillis()}.tosqbk")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure(token, "Could not create this backup. Keep your existing recovery materials. Check authentication and the TOS node, then retry.") }
            finally { pass.fill('\u0000'); prepared?.let { runCatching { files.discard(it) } } }
        }
    }
    private fun failure(token: Long, message: String) {
        if (token != generation) return
        reset(); status.text = message
    }
}
