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

/** Recovery controls and replacement signing. No operation here installs keys or permits payment. */
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
    private var migrationPlan: QuantumPreparedSuccessorMigration? = null
    private var migrationQuote: QuantumMigrationQuote? = null
    private var fundingOperation: java.util.UUID? = null
    private var fundingQuote: QuantumProbeFundingQuote? = null
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
        button("Authenticate and verify funded replacement keys", "quantum.recovery.successor.funded") { fundedSuccessor() }
        button("Save encrypted replacement-key backup", "quantum.recovery.successor.backup") {
            navigation?.add(network.tos.wallet.app.ui.screen.backup.main.QuantumBackupExportScreen(wallet, successor = true))
        }
        button("Verify saved replacement-key backup record", "quantum.recovery.successor.backup.verify") {
            run {
                authenticating = true
                val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Verify replacement-key backup") }
                    finally { authenticating = false }
                if (!accepted) return@run
                check(viewLifecycleOwnerLiveData.value?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                val digest = accounts.verifiedFundedQuantumSuccessorBackup(wallet)
                status.text = "Saved replacement-key backup record verified.\nFile SHA-256: $digest\nKeep the file, its password and the old backup. Replacement keys are not installed; payments remain restricted."
            }
        }
        button("Review same-address key replacement", "quantum.recovery.migration.review") {
            run {
                migrationPlan = null
                migrationQuote = null
                authenticating = true
                val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Review key replacement") }
                    finally { authenticating = false }
                if (!accepted) return@run
                check(viewLifecycleOwnerLiveData.value?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                val migration = accounts.prepareFundedQuantumSuccessorMigration(wallet)
                migrationPlan = migration
                fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
                status.text = "Unsigned key replacement prepared.\nOriginal address: ${wallet.accountId}" +
                    "\nRequest: ${hex(migration.request.digest)}" +
                    "\nReplacement authority: ${hex(migration.successor.hash)}\nSaved backup SHA-256: ${hex(migration.confirmedBackupDigest)}" +
                    "\nNew epoch: ${migration.expectedState.epoch}\nExpires: ${java.util.Date(migration.expiresAt * 1000)}" +
                    "\nNothing has been signed or sent. Replacement keys are not installed; payments remain restricted."
            }
        }
        button("Authenticate and sign key replacement", "quantum.recovery.migration.sign") {
            run {
                val reviewed = checkNotNull(migrationPlan) { "Review key replacement first" }
                migrationQuote = null
                authenticating = true
                val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Sign reviewed key replacement") }
                    finally { authenticating = false }
                if (!accepted) return@run
                check(migrationPlan === reviewed && viewLifecycleOwnerLiveData.value?.lifecycle?.currentState ==
                    androidx.lifecycle.Lifecycle.State.RESUMED)
                try { accounts.signFundedQuantumSuccessorMigration(wallet, reviewed) }
                finally { migrationPlan = null }
                showMigration(accounts.pendingFundedQuantumSuccessorMigration(wallet))
            }
        }
        button("Reload saved key replacement", "quantum.recovery.migration.reload") {
            run {
                migrationPlan = null
                migrationQuote = null
                showMigration(accounts.pendingFundedQuantumSuccessorMigration(wallet))
            }
        }
        button("Estimate saved key replacement", "quantum.recovery.migration.estimate") {
            run {
                migrationQuote = null
                val estimate = accounts.estimateFundedQuantumSuccessorMigration(wallet, BigInteger("100000000"))
                migrationQuote = estimate
                status.text = "Key replacement estimate ready.\nRequest: ${estimate.requestHash}" +
                    "\nReplacement authority: ${estimate.replacementAuthorityHash}\nEstimated fee: ${estimate.estimatedFee} nanoTOS" +
                    "\nExpires: ${java.util.Date(estimate.expiresAt * 1000)}" +
                    "\nThis is a local estimate. Nothing has been sent. Replacement keys are not installed; payments remain restricted."
            }
        }
        button("Prepare possession challenge", "quantum.recovery.probe.prepare") { prepareProbe() }
        button("Sign possession message", "quantum.recovery.probe.sign") { signProbe() }
        button("Reload possession message", "quantum.recovery.probe.reload") {
            run { showProbe(accounts.pendingQuantumRecoveryProbe(wallet)) }
        }
        button("Review 0.01 TOS key-check funding", "quantum.recovery.funding.review") { reviewFunding() }
        button("Authenticate and sign funding", "quantum.recovery.funding.sign") { signFunding() }
        button("Estimate saved funding", "quantum.recovery.funding.estimate") { estimateFunding() }
        button("Confirm fee and send funding", "quantum.recovery.funding.send") { confirmFundingSend() }
        button("Verify and record key-check result", "quantum.recovery.funding.verify") {
            run {
                val saved = checkNotNull(accounts.pendingQuantumProbeFunding(wallet))
                if (saved.phase == "COMPLETED") showFunding(saved)
                else {
                    val result = accounts.readQuantumProbeFundingOutcome(wallet, saved.operation)
                    if (result.status == network.tos.wallet.data.account.pq.QuantumProbeFundingOutcomeReader.Status.Verified &&
                        result.currentStateMatchesExpected == true) {
                        accounts.completeQuantumProbeFunding(wallet, result)
                        showFunding(accounts.pendingQuantumProbeFunding(wallet))
                    } else status.text = "Key-check result: ${result.status}. Current state matches: ${result.currentStateMatchesExpected}. Payments remain restricted."
                }
            }
        }
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
            fundingQuote = null
            actions.forEach { it.isEnabled = false }
            try { action() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) diagnosticFailureStack =
                    (error.javaClass.name + "\n" + error.stackTrace.take(16).joinToString("\n")).take(8192)
                plan = null; quote = null; fundingPlan = null; fundingOperation = null; fundingQuote = null
                status.text = if (error is QuantumPriorControlNotExpired)
                    "The previous authorization has not yet expired at the proven chain time. Wait, then try this step again. Nothing was signed or sent."
                else "Unable to complete this step. Reload the saved operation before continuing. Recovery controls currently require an imported encrypted backup with matching rescue material. Nothing was unlocked."
            } finally {
                actions.forEach { it.isEnabled = true }
                status.post {
                    if (status.isAttachedToWindow) status.requestRectangleOnScreen(
                        android.graphics.Rect(0, 0, status.width, minOf(status.height, 96)), false)
                }
            }
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
    private fun fundedSuccessor() = run {
        if (accounts.pendingQuantumProbeFunding(wallet)?.phase != "COMPLETED") {
            status.text = "Verify and record key-check funding before checking replacement keys."
            return@run
        }
        authenticating = true
        val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Verify funded replacement keys") }
            finally { authenticating = false }
        if (!accepted) return@run
        check(viewLifecycleOwnerLiveData.value?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
        val staged = accounts.inspectFundedQuantumSuccessorMaterial(wallet)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        status.text = "Saved replacement keys match verified funding." +
            "\nAuthority: ${hex(staged.inspection.snapshot.hash)}\nMaterial: ${hex(staged.inspection.encryptedDigest)}" +
            "\nKeys are not installed. Key replacement and a new backup remain required. Payments remain restricted."
    }
    private fun prepareProbe() = run {
        accounts.requireQuantumProbePreparationReady(wallet)
        status.text = "Ready to prepare the replacement-key check. No challenge has been saved."
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
                if (it.phase == "SUBMITTING" || it.phase == "UNKNOWN")
                    "\nA submission may have reached the network. Do not send it again. Its chain outcome and key check remain unverified. Payments remain restricted."
                else if (it.phase == "COMPLETED") "\nFunding and key check were verified and recorded. Key replacement and a new backup remain required. Payments remain restricted."
                else "\nThis is a local signing record, not a chain result. Nothing was sent by this step. Payments remain restricted."
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
    private fun estimateFunding() = run {
        fundingPlan = null; fundingOperation = null
        val saved = accounts.pendingQuantumProbeFunding(wallet)
        if (saved?.phase != "SIGNED") { showFunding(saved); return@run }
        val estimate = accounts.estimateQuantumProbeFunding(wallet, saved.operation, BigInteger("100000000"))
        fundingQuote = estimate
        fun tos(value: BigInteger) = java.math.BigDecimal(value, 9).stripTrailingZeros().toPlainString()
        status.text = "Key-check funding estimate ready." +
            "\nFrom wallet: ${wallet.address}\nProbe: ${estimate.address}" +
            "\nSend value: ${tos(estimate.amount)} TOS\nEstimated fee: ${tos(estimate.estimatedFee)} TOS" +
            "\nEstimated total debit: ${tos(estimate.estimatedDebit)} TOS" +
            "\nExpires: ${java.util.Date(estimate.expiresAt * 1000)}" +
            "\nThis is a local estimate. Nothing has been sent or approved. Recovery and payments remain restricted."
    }
    private fun confirmFundingSend() {
        val estimate = fundingQuote
        if (estimate == null) { status.text = "Estimate the saved funding before confirming its fee."; return }
        if (System.currentTimeMillis() / 1000 >= estimate.expiresAt) {
            fundingQuote = null; status.text = "Funding estimate expired. Estimate the saved funding again."; return
        }
        fun tos(value: BigInteger) = java.math.BigDecimal(value, 9).stripTrailingZeros().toPlainString()
        confirmation = AlertDialog.Builder(requireContext()).setTitle("Send key-check funding?")
            .setMessage("From wallet: ${wallet.address}\nProbe: ${estimate.address}\nSend value: ${tos(estimate.amount)} TOS" +
                "\nEstimated fee: ${tos(estimate.estimatedFee)} TOS\nEstimated total debit: ${tos(estimate.estimatedDebit)} TOS" +
                "\nExpires: ${java.util.Date(estimate.expiresAt * 1000)}\nActual fees can change. This funds the replacement-key check; recovery and payments stay restricted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Send funding") { _, _ ->
                if (fundingQuote !== estimate || viewLifecycleOwnerLiveData.value?.lifecycle?.currentState
                        ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) != true) {
                    status.text = "Estimate the saved funding before confirming its fee."
                } else run {
                    status.text = "Submitting key-check funding. Its outcome is unverified. Do not send it again."
                    try { accounts.submitQuantumProbeFunding(estimate) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        val saved = accounts.pendingQuantumProbeFunding(wallet)
                        if (saved?.phase == "SUBMITTING" || saved?.phase == "UNKNOWN") { showFunding(saved); return@run }
                        throw error
                    }
                    showFunding(accounts.pendingQuantumProbeFunding(wallet))
                }
            }.create().also { it.show() }
    }
    private fun showMigration(saved: AccountRepository.MigrationSigningStatus?) {
        status.text = when (saved?.phase) {
            null -> "No key replacement is saved. Review it before authenticating."
            "RESERVED" -> "Key replacement is reserved with its original deadline. No signature is saved."
            "SIGNING" -> "Key replacement signing is incomplete or uncertain. Do not sign it again."
            "SIGNED" -> "Saved key replacement signature verified. Nothing has been sent. Replacement keys are not installed; payments remain restricted."
            else -> error("Unknown migration signing phase")
        } + if (saved == null) "" else "\nRequest: ${saved.requestHash}\nExpires: ${java.util.Date(saved.deadline * 1000)}" +
            "\nThis checks the saved record, not current chain state or execution."
    }
    override fun onPause() {
        migrationQuote = null
        if (fundingQuote != null) {
            fundingQuote = null
            status.text = "Funding estimate cleared. Estimate the saved funding again before continuing."
        }
        if (!authenticating) { operation?.cancel(); confirmation?.dismiss(); confirmation = null; plan = null; quote = null; fundingPlan = null; fundingOperation = null; migrationPlan = null }
        super.onPause()
    }
    override fun onDestroyView() {
        diagnosticFailureStack = null
        migrationPlan = null
        migrationQuote = null
        operation?.cancel(); confirmation?.dismiss(); confirmation = null; plan = null; quote = null; fundingPlan = null; fundingOperation = null; fundingQuote = null; actions.clear()
        super.onDestroyView()
    }
}
