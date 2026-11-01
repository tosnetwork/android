package network.tos.wallet.app.ui.screen.pq

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import network.tos.wallet.app.R
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.*
import network.tos.wallet.data.passcode.PasscodeManager
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.navigation.NavigationActivity
import java.math.BigInteger

/** First recovery step only. Completion never permits ordinary payment or replaces keys. */
class QuantumRecoveryControlScreen(wallet: WalletEntity) : WalletContextScreen(R.layout.fragment_pq_wallets, wallet) {
    override val fragmentName = "QuantumRecoveryControlScreen"
    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()
    private val accounts: AccountRepository by inject()
    private val passcodes: PasscodeManager by inject()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var operation: Job? = null
    private var authenticating = false
    private var plan: QuantumPreparedRecoveryControl? = null
    private var quote: QuantumRecoveryControlQuote? = null
    private var fundingPlan: QuantumPreparedProbeFunding? = null
    private var fundingOperation: java.util.UUID? = null
    private var confirmation: AlertDialog? = null
    // Test diagnostics only: class/frames, never exception messages or input values.
    private var diagnosticFailureStack: String? = null
    private val actions = mutableListOf<Button>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.pq_content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        text("Secure recovered wallet")
        text("${wallet.label.name}\n${wallet.address}")
        text("Locking disables the current primary key. It does not replace your keys or finish recovery. Payments stay restricted until recovery and a new backup are complete.")
        status = text("Loading saved recovery operation…").apply { contentDescription = "quantum.recovery.status" }
        button("Review primary-key lock", "quantum.recovery.review") {
            run {
                check(accounts.pendingQuantumRecoveryControl(wallet) == null) { "A recovery operation is already saved" }
                plan = accounts.prepareQuantumRecoveryControl(wallet, QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY)
                status.text = "Ready to authenticate a primary-key lock. No message has been signed or sent."
            }
        }
        button("Authenticate and sign lock", "quantum.recovery.sign") { sign() }
        button("Estimate saved lock", "quantum.recovery.estimate") {
            run {
                quote = accounts.estimateQuantumRecoveryControl(wallet, BigInteger("100000000"))
                status.text = "Estimated fee: ${quote!!.estimatedFee} nanoTOS. Quote expires: ${java.util.Date(quote!!.expiresAt * 1000)}. Confirm separately to send."
            }
        }
        button("Confirm fee and send lock", "quantum.recovery.send") { confirmSend() }
        button("Verify and record lock result", "quantum.recovery.verify") {
            run {
                val result = accounts.readQuantumRecoveryControlOutcome(wallet)
                if (result.status == QuantumRecoveryControlOutcomeReader.Status.Executed && result.currentStateMatchesExpected == true) {
                    accounts.completeQuantumRecoveryControl(wallet, result)
                    status.text = "Recovery control verified and recorded. Recovery is still restricted; key replacement and a new backup remain required."
                } else status.text = "Result: ${result.status}. Current state matches: ${result.currentStateMatchesExpected}. Nothing was released or resent."
            }
        }
        button("Reload saved operation", "quantum.recovery.reload") { reload() }
        button("Generate replacement keys", "quantum.recovery.successor.generate") { successor(false) }
        button("Verify saved replacement keys", "quantum.recovery.successor.resume") { successor(true) }
        button("Prepare possession challenge", "quantum.recovery.probe.prepare") { prepareProbe() }
        button("Sign possession message", "quantum.recovery.probe.sign") { signProbe() }
        button("Reload possession message", "quantum.recovery.probe.reload") {
            run { showProbe(accounts.pendingQuantumRecoveryProbe(wallet)) }
        }
        button("Review 0.01 TOS key-check funding", "quantum.recovery.funding.review") { reviewFunding() }
        button("Authenticate and sign funding", "quantum.recovery.funding.sign") { signFunding() }
        button("Reload funding message", "quantum.recovery.funding.reload") {
            run { fundingPlan = null; fundingOperation = null; showFunding(accounts.pendingQuantumProbeFunding(wallet)) }
        }
        button("Close", "quantum.recovery.close") { finish() }
        reload()
    }
    private fun text(value: String) = TextView(requireContext()).apply {
        text = value; textSize = 17f; setPadding(0, 12, 0, 12); content.addView(this)
    }
    private fun button(value: String, id: String, action: () -> Unit) {
        val button = Button(requireContext()).apply {
            text = value; isAllCaps = false; contentDescription = id
            setOnClickListener { if (operation?.isActive != true) action() }
        }
        actions.add(button); content.addView(button)
    }
    private fun run(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewLifecycleOwner.lifecycleScope.launch {
            diagnosticFailureStack = null
            actions.forEach { it.isEnabled = false }
            try { action() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) diagnosticFailureStack =
                    (error.javaClass.name + "\n" + error.stackTrace.take(16).joinToString("\n")).take(8192)
                plan = null; quote = null; fundingPlan = null; fundingOperation = null
                status.text = if (error is QuantumPriorControlNotExpired)
                    "The previous authorization has not yet expired at the proven chain time. Wait, then review funding again. Nothing was signed or sent."
                else "Unable to complete this step. Reload the saved operation before continuing. Recovery controls currently require an imported encrypted backup with matching rescue material. Nothing was unlocked."
            } finally { actions.forEach { it.isEnabled = true } }
        }
    }
    private fun reload() = run {
        plan = null; quote = null
        val saved = accounts.pendingQuantumRecoveryControl(wallet)
        status.text = saved?.let { "Saved recovery operation: ${it.operation}\nStatus: ${it.phase}\nMessage: ${it.messageRootHash ?: "not signed"}\nPayments remain restricted." }
            ?: "No saved recovery control. Review the lock before authenticating."
    }
    private fun sign() {
        val prepared = plan
        if (prepared == null) { status.text = "Review the lock before authenticating."; return }
        run {
            authenticating = true
            val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Lock recovered wallet primary key") }
                finally { authenticating = false }
            if (!accepted) return@run
            val id = accounts.reserveQuantumRecoveryControl(prepared)
            accounts.signQuantumRecoveryControl(prepared, id)
            plan = null
            status.text = "Lock signed and saved. Estimate its fee before sending."
        }
    }
    private fun confirmSend() {
        val estimate = quote
        if (estimate == null) { status.text = "Estimate the saved lock before confirming its fee."; return }
        confirmation = AlertDialog.Builder(requireContext()).setTitle("Send primary-key lock?")
            .setMessage("Wallet: ${wallet.address}\nEstimated fee: ${estimate.estimatedFee} nanoTOS. Actual fees can change. This disables the current primary key. Recovery remains restricted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Send lock") { _, _ ->
                run {
                    quote = null
                    accounts.submitQuantumRecoveryControl(estimate)
                    status.text = "Lock submitted; its outcome is unverified. Verify the saved result. Do not send it again."
                }
            }.create().also { it.show() }
    }
    private fun successor(resume: Boolean) = run {
        if (accounts.pendingQuantumRecoveryControl(wallet)?.phase != "COMPLETED") {
            status.text = "Verify and record the primary-key lock before preparing replacement keys."
            return@run
        }
        authenticating = true
        val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity,
            if (resume) "Verify saved replacement keys" else "Generate replacement keys") }
            finally { authenticating = false }
        if (!accepted) return@run
        val staged = accounts.stageQuantumSuccessorMaterial(wallet, resume)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        status.text = (if (resume) "Saved replacement keys verified." else "Replacement keys saved on this device.") +
            "\nAuthority: ${hex(staged.inspection.snapshot.hash)}\nMaterial: ${hex(staged.inspection.encryptedDigest)}" +
            "\nThese keys are not installed. Recovery and payments remain restricted. Keep this app data until the new backup is complete."
    }
    private fun showProbe(probe: AccountRepository.RecoveryProbeStatus?) {
        status.text = probe?.let {
            "Possession message: ${it.phase}\nOperation: ${it.operation}\nProbe: ${it.address}\nChallenge digest: ${it.digest}" +
                "\nMaterial: ${it.materialDigest}\nDeadline: ${java.util.Date(it.deadline * 1000)}" +
                "\nThis is a local message. On-chain possession is not verified; keys are not installed and payments remain restricted."
        } ?: "No saved possession challenge. Prepare one after saving replacement keys."
    }
    private fun prepareProbe() = run {
        authenticating = true
        val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Prepare possession challenge") }
            finally { authenticating = false }
        if (!accepted) return@run
        showProbe(accounts.reserveQuantumRecoveryProbe(wallet))
    }
    private fun signProbe() = run {
        val saved = accounts.pendingQuantumRecoveryProbe(wallet)
        if (saved?.phase != "RESERVED") { showProbe(saved); return@run }
        authenticating = true
        val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Sign replacement-key possession message") }
            finally { authenticating = false }
        if (!accepted) return@run
        accounts.signQuantumRecoveryProbe(wallet, saved.operation)
        showProbe(accounts.pendingQuantumRecoveryProbe(wallet))
    }
    private fun showFunding(saved: AccountRepository.RecoveryProbeFundingStatus?) {
        status.text = saved?.let {
            "Funding message: ${it.phase}\nOperation: ${it.operation}" +
                "\nThis is a local signing record, not a chain result. Nothing was sent by this step. Payments remain restricted."
        } ?: "No saved funding message. Review the key-check funding before authenticating."
    }
    private fun reviewFunding() = run {
        val saved = accounts.pendingQuantumProbeFunding(wallet)
        if (saved != null && saved.phase != "RESERVED") {
            fundingPlan = null; fundingOperation = null; showFunding(saved); return@run
        }
        val prepared = if (saved != null) accounts.resumeQuantumProbeFunding(wallet, saved.operation)
            else accounts.prepareQuantumProbeFunding(wallet, BigInteger("10000000"))
        fundingPlan = prepared; fundingOperation = saved?.operation
        status.text = "Ready to authenticate key-check funding." +
            "\nSend value: ${java.math.BigDecimal(prepared.amount, 9).stripTrailingZeros().toPlainString()} TOS. Wallet gas fees are additional and must be estimated and confirmed before sending." +
            "\nProbe: ${prepared.probeAddressText}\nExpires: ${java.util.Date(prepared.expiresAt * 1000)}" +
            "\nSigning alone sends nothing. The key check and replacement keys remain unverified on chain."
    }
    private fun signFunding() = run {
        val saved = accounts.pendingQuantumProbeFunding(wallet)
        if (saved != null && saved.phase != "RESERVED") {
            fundingPlan = null; fundingOperation = null; showFunding(saved); return@run
        }
        val prepared = fundingPlan
        if (prepared == null) { status.text = "Review the key-check funding before authenticating."; return@run }
        check(saved?.operation == fundingOperation)
        prepared.requireFresh(System.currentTimeMillis() / 1000)
        authenticating = true
        val amount = java.math.BigDecimal(prepared.amount, 9).stripTrailingZeros().toPlainString()
        val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Sign $amount TOS key-check funding") }
            finally { authenticating = false }
        if (!accepted) return@run
        val operationId = saved?.operation ?: accounts.reserveQuantumProbeFunding(prepared)
        fundingPlan = null; fundingOperation = null
        accounts.signQuantumProbeFunding(wallet, operationId, prepared.request.digest)
        showFunding(accounts.pendingQuantumProbeFunding(wallet))
    }
    override fun onPause() {
        if (!authenticating) { operation?.cancel(); confirmation?.dismiss(); confirmation = null; plan = null; quote = null; fundingPlan = null; fundingOperation = null }
        super.onPause()
    }
    override fun onDestroyView() {
        diagnosticFailureStack = null
        operation?.cancel(); confirmation?.dismiss(); confirmation = null; plan = null; quote = null; fundingPlan = null; fundingOperation = null; actions.clear()
        super.onDestroyView()
    }
}
