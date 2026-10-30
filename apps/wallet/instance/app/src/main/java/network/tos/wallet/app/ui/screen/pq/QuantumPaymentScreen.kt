package network.tos.wallet.app.ui.screen.pq

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import network.tos.blockchain.ton.TonAddressTags
import network.tos.blockchain.ton.contract.QuantumPaymentIntent
import network.tos.wallet.app.R
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.*
import network.tos.wallet.data.passcode.PasscodeManager
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.ton.block.AddrStd
import uikit.navigation.NavigationActivity
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date

/** Actual Quantum route. It never enters the classical Ed25519 send/sign screen.
 * Sign/send remain governed by the wallet's product availability gate.
 */
class QuantumPaymentScreen(wallet: WalletEntity) : WalletContextScreen(R.layout.fragment_pq_wallets, wallet) {
    override val fragmentName = "QuantumPaymentScreen"
    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()
    private val accounts: AccountRepository by inject()
    private val passcodes: PasscodeManager by inject()
    private lateinit var layout: LinearLayout
    private lateinit var recipient: EditText
    private lateinit var amount: EditText
    private lateinit var comment: EditText
    private lateinit var bounce: CheckBox
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var review: Button
    private lateinit var sign: Button
    private lateinit var submit: Button
    private lateinit var refreshQuote: Button
    private lateinit var recoverQuote: Button
    private lateinit var verify: Button
    private lateinit var older: Button
    private lateinit var configureRpc: Button
    private lateinit var discard: Button
    private lateinit var endAuthorization: Button
    private lateinit var newPayment: Button
    private lateinit var localHistory: TextView
    private var operation: Job? = null
    private var authenticating = false
    private var prepared: QuantumPreparedPayment? = null
    private var quote: QuantumPaymentQuote? = null
    private var pending: QuantumPaymentOutbox.Entry? = null
    private var cursor: QuantumPaymentOutcomeReader.Cursor? = null
    private var journalLoaded = false
    private var retiring = false
    private var ended = false
    private var receipt: QuantumPaymentOutcomeReader.Receipt? = null
    private var serial = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        require(wallet.isQuantum)
        layout = view.findViewById(R.id.pq_content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        label("Quantum payment")
        label("${wallet.label.name}\n${wallet.address}")
        recipient = input("Recipient wallet address", "quantum.payment.recipient").apply { setText(arguments?.getString("recipient")) }
        amount = input("Amount (TOS)", "quantum.payment.amount").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(arguments?.getString("amount"))
        }
        comment = input("Comment (optional)", "quantum.payment.comment").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; setText(arguments?.getString("comment"))
        }
        bounce = CheckBox(requireContext()).apply { text = "Bounce if the recipient cannot accept"; contentDescription = "quantum.payment.bounce" }
        layout.addView(bounce)
        status = label("Loading pending payment…", "quantum.payment.status")
        details = label("", "quantum.payment.details")
        review = button("Review payment", "quantum.payment.review") { prepare() }
        sign = button("Sign and estimate locally", "quantum.payment.sign") { authorize() }
        submit = button("Confirm fee and send", "quantum.payment.submit") { send() }
        refreshQuote = button("Refresh estimate for the same message", "quantum.payment.refreshQuote") { refreshEstimate() }
        recoverQuote = button("Re-estimate saved signed message", "quantum.payment.recoverQuote") { estimateSaved() }
        verify = button("Verify payment result", "quantum.payment.verify") { outcome(false) }
        older = button("Check older transactions", "quantum.payment.older") { outcome(true) }
        configureRpc = button("Configure TOS node", "quantum.payment.configureRpc") {
            navigation?.add(network.tos.wallet.app.ui.screen.settings.main.SettingsScreen.newInstance(wallet, "quantum-payment"))
        }.apply { visibility = View.GONE }
        discard = button("Discard unsigned request", "quantum.payment.discard") { discardUnsigned() }
        endAuthorization = button("Verify expiry and end this authorization", "quantum.payment.end") { endExpired() }
        newPayment = button("Start another payment", "quantum.payment.new") { startAnotherPayment() }
        button("Reload pending payment", "quantum.payment.reload") { loadJournal() }
        button("Show local authorization history", "quantum.payment.localHistory") { showLocalHistory() }
        localHistory = label("", "quantum.payment.localHistory.details")
        button("Close", "quantum.payment.close") { finish() }
        render(); loadJournal()
    }
    override fun onResume() {
        super.onResume()
        if (::layout.isInitialized && operation?.isActive != true && journalLoaded) loadJournal()
    }
    override fun onPause() {
        // The existing passcode screen may cover this screen; its successful
        // return must still be followed by freshness and availability checks.
        if (!authenticating) {
            serial++; operation?.cancel(); operation = null; prepared = null; quote = null; cursor = null; receipt = null
        }
        super.onPause()
    }
    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden && !authenticating) {
            serial++; operation?.cancel(); operation = null; prepared = null; quote = null; cursor = null; receipt = null
        }
        super.onHiddenChanged(hidden)
    }
    override fun onDestroyView() { serial++; operation?.cancel(); operation = null; super.onDestroyView() }
    private fun label(text: String, id: String? = null): TextView = TextView(requireContext()).apply {
        this.text = text; textSize = 17f; setPadding(0, 12, 0, 12); contentDescription = id
        this@QuantumPaymentScreen.layout.addView(this)
    }
    private fun input(hint: String, id: String): EditText = EditText(requireContext()).apply {
        this.hint = hint; contentDescription = id; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        this@QuantumPaymentScreen.layout.addView(this)
    }
    private fun button(text: String, id: String, action: () -> Unit): Button = Button(requireContext()).apply {
        this.text = text; contentDescription = id; isAllCaps = false
        setOnClickListener { if (operation?.isActive != true) action() }; this@QuantumPaymentScreen.layout.addView(this)
    }
    private fun run(message: String, body: suspend () -> Unit) {
        if (operation?.isActive == true) return
        val tag = ++serial
        status.text = message
        operation = viewLifecycleOwner.lifecycleScope.launch {
            try { body() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isAdded && serial == tag) {
                    status.text = if (error.message == "Original authorization is not proven strictly expired")
                        "The original authorization has not been proven expired. Quote expiry and the local clock cannot end it. Reload and verify again after fresh shard time passes its original deadline."
                    else "Unable to complete this step. Reload to verify the saved authorization and retirement state before trying again."
                    journalLoaded = false; prepared = null; quote = null; cursor = null
                }
            } finally { if (serial == tag && isAdded) { operation = null; render() } }
        }
        render()
    }
    private fun loadJournal() = run("Loading the original authorization…") {
        val stored = accounts.quantumRetirementEntry(wallet)
        val recovering = accounts.quantumRetirementStatus(wallet).recovering
        // A durable end can finish while this view is paused/cancelled. Local records only restore
        // the UI consent gate; they never authorize deleting or releasing an active entry.
        val records = if (stored == null) accounts.quantumLocalPaymentHistory(wallet) else emptyList()
        currentCoroutineContext().ensureActive()
        pending = stored; retiring = recovering; journalLoaded = true; prepared = null; quote = null; cursor = null; receipt = null
        configureRpc.visibility = View.GONE
        if (stored == null) {
            ended = ended || records.any { it.state == QuantumAuthorizationRetirement.State.RELEASE_COMPLETED }
            if (ended) { clearIntent(); localHistory.text = historyText(records) }
            details.text = ""; status.text = if (ended) "The old authorization ended. Choose Start another payment to enter a new intent."
                else if (wallet.isQuantumSelfPayAvailable) "Enter a payment to review." else "Quantum payment signing is not available yet."
        }
        else {
            val intent = stored.intent
            recipient.setText(recipientAddress(intent)); amount.setText(tosInput(intent.amount)); comment.setText(intent.comment)
            bounce.isChecked = intent.bounce
            details.text = summary(intent) + "\nAuthorization: ${stored.id}\nStatus: ${stored.phase.name.lowercase()}\nOriginal authorization deadline: ${time(stored.deadline)}"
            status.text = if (recovering) "Ending this authorization was interrupted. Verify its original expiry to finish safely; no new payment can be signed yet."
                else if (stored.signedBOC == null) "Signing may have been interrupted. This authorization remains reserved."
                else "Payment outcome is unverified. Verify the original message; a relay response is not a receipt."
            if (!recovering && stored.signedBOC != null) {
                try {
                    readPaymentOutcome(continueHistory = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    status.text = "Payment result could not be verified. The saved authorization remains unchanged; verify again when connected."
                    configureRpc.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun prepare() {
        if (!journalLoaded || pending != null || ended) return
        val intent = try {
            val addressText = recipient.text.toString().trim()
            val address = AddrStd.parse(addressText)
            val tags = TonAddressTags.of(addressText)
            require(tags.isTestnet != true || wallet.testnet) { "Test-only address on a main network wallet" }
            if (tags.userFriendly) bounce.isChecked = tags.isBounceable
            QuantumPaymentIntent(address, QuantumPaymentIntent.parseTos(amount.text.toString().trim()), bounce.isChecked, comment.text.toString())
        } catch (_: Exception) {
            status.text = "Use a full wallet address, a positive TOS amount with at most nine decimal places, and a comment up to 4096 UTF-8 bytes."
            return
        }
        run("Verifying the wallet and payment intent…") {
            val result = accounts.prepareQuantumPayment(wallet, intent)
            currentCoroutineContext().ensureActive()
            prepared = result
            details.text = summary(intent) + "\nOriginal authorization deadline: ${time(result.expiresAt)}" +
                if (prepared!!.requiresDeployment) "\nThe first payment also deploys this wallet using its balance." else ""
            status.text = "Review the exact recipient, amount, bounce setting and comment. Signing and estimation do not broadcast."
        }
    }
    private fun authorize() {
        val payment = prepared ?: return
        if (!wallet.isQuantumSelfPayAvailable || pending != null) return
        run("Authenticate to sign this exact payment for local estimation…") {
            authenticating = true
            val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Sign and estimate this Quantum payment") }
                finally { authenticating = false }
            currentCoroutineContext().ensureActive()
            if (!accepted) { status.text = "Authentication cancelled. Nothing was broadcast."; return@run }
            check(wallet.isQuantumSelfPayAvailable)
            quote = accounts.authorizeQuantumPayment(wallet, payment)
            pending = accounts.pendingQuantumPayment(wallet)
            currentCoroutineContext().ensureActive()
            showQuote(checkNotNull(quote))
        }
    }
    private fun showQuote(value: QuantumPaymentQuote) {
        val deadline = checkNotNull(pending).deadline
        details.text = summary(value.intent) + "\nEstimated fee: ${format(value.estimatedFee)}\nEstimated total debit: ${format(value.estimatedDebit)}\nEstimated remaining balance: ${format(value.estimatedBalance)}\nFee quote expires: ${time(value.expiresAt)}\nOriginal authorization deadline: ${time(deadline)}"
        status.text = "Estimated fees can change. Confirm this displayed estimate to broadcast the exact signed message."
    }
    private fun send() {
        val value = quote ?: return
        if (!wallet.isQuantumSelfPayAvailable) return
        run("Saving fee confirmation and submitting the original message…") {
            try { accounts.submitQuantumPayment(wallet, value) }
            finally { quote = null; prepared = null }
            pending = accounts.pendingQuantumPayment(wallet)
            status.text = "Message submitted. Payment outcome remains unverified; verify it from authenticated transactions."
        }
    }
    private fun refreshEstimate() {
        val value = quote ?: return
        if (!wallet.isQuantumSelfPayAvailable) return
        run("Re-estimating the same signed message…") { quote = accounts.refreshQuantumPayment(wallet, value); showQuote(checkNotNull(quote)) }
    }
    private fun estimateSaved() {
        if (!wallet.isQuantumSelfPayAvailable || pending?.phase != QuantumPaymentOutbox.Phase.SIGNED) return
        run("Authenticate to re-estimate the original signed message…") {
            authenticating = true
            val accepted = try { passcodes.confirmation(requireActivity() as NavigationActivity, "Re-estimate saved Quantum payment") }
                finally { authenticating = false }
            currentCoroutineContext().ensureActive()
            if (!accepted) { status.text = "Authentication cancelled."; return@run }
            check(wallet.isQuantumSelfPayAvailable)
            quote = accounts.estimatePendingQuantumPayment(wallet)
            pending = accounts.pendingQuantumPayment(wallet)
            currentCoroutineContext().ensureActive()
            showQuote(checkNotNull(quote))
        }
    }
    private fun outcome(continueHistory: Boolean) {
        if (pending?.signedBOC == null || (continueHistory && cursor == null)) return
        run("Verifying the original message in authenticated history…") {
            try {
                readPaymentOutcome(continueHistory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                status.text = "Payment result could not be verified. The saved authorization remains unchanged; configure the TOS node or retry when connected."
                configureRpc.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun readPaymentOutcome(continueHistory: Boolean) {
        val entry = checkNotNull(pending)
        val page = accounts.readQuantumRetirementOutcome(wallet, if (continueHistory) cursor else null)
        currentCoroutineContext().ensureActive()
        check(page.operationId == entry.id)
        configureRpc.visibility = View.GONE
        cursor = page.next
        val observedReceipt = page.receipt
        receipt = observedReceipt
        status.text = when {
            observedReceipt?.outcome == QuantumPaymentOutcomeReader.Outcome.SenderSent -> "The wallet sent the exact transfer. Recipient credit is a separate observation."
            observedReceipt != null -> "The request was included, but this transaction does not confirm the exact transfer."
            page.authorizationExpired -> "The authorization has expired. The payment outcome is still unverified."
            else -> "No matching payment found in this page. The outcome remains unverified."
        }
        observedReceipt?.let {
            details.text = summary(entry.intent) + "\nTransaction: ${it.transactionHash}\nLogical time: ${it.logicalTime}\nOriginal authorization deadline: ${time(entry.deadline)}"
        }
    }
    private fun endExpired() {
        val entry = pending ?: return
        AlertDialog.Builder(requireContext()).setTitle("Verify expiry and end this authorization?")
            .setMessage("Original deadline: ${time(entry.deadline)}. A fresh authenticated shard proof must be strictly later. If the payment result is unverified, ending the old authorization does not mean the payment failed or that no funds moved. Nothing is signed or broadcast. A new payment requires a separate decision.")
            .setNegativeButton("Keep", null).setPositiveButton("Verify and end") { _, _ ->
                run("Verifying original expiry and saving the final local record…") {
                    prepared = null; quote = null; cursor = null
                    val completed = accounts.endExpiredQuantumAuthorization(wallet, entry.id, receipt)
                    currentCoroutineContext().ensureActive()
                    pending = null; retiring = false; receipt = null; journalLoaded = true; ended = true
                    clearIntent()
                    details.text = "Ended authorization: ${completed.operationId}\n" + resultText(completed)
                    status.text = "The old authorization has ended. Start another payment only if you explicitly want a new transfer."
                    localHistory.text = historyText(accounts.quantumLocalPaymentHistory(wallet))
                }
            }.show()
    }
    private fun startAnotherPayment() {
        if (!journalLoaded || pending != null || !ended) return
        ended = false; prepared = null; quote = null; receipt = null; cursor = null
        clearIntent()
        details.text = ""; status.text = "Enter a new payment and review it explicitly."
        render()
    }
    private fun clearIntent() { recipient.setText(""); amount.setText(""); comment.setText(""); bounce.isChecked = false }
    private fun showLocalHistory() = run("Loading saved local authorization records…") {
        localHistory.text = historyText(accounts.quantumLocalPaymentHistory(wallet))
        status.text = "These are saved local records. They do not re-authenticate the current chain or prove recipient credit."
    }
    private fun historyText(records: List<QuantumAuthorizationRetirement.Summary>): String =
        if (records.isEmpty()) "No saved local authorization records."
        else "Local authorization history (up to 64 saved records):\n\n" + records.joinToString("\n\n") {
            "${it.operationId}\n${format(it.intent.amount)} to ${recipientAddress(it.intent)}\n${resultText(it)}\n" +
                (if (it.state == QuantumAuthorizationRetirement.State.RELEASE_COMPLETED) "Retirement completed locally." else "Retirement pending; the authorization remains blocked.") +
                (it.transactionHash?.let { hash -> "\nSender transaction: $hash" } ?: "")
        }
    private fun resultText(record: QuantumAuthorizationRetirement.Summary): String = when (record.result) {
        QuantumAuthorizationRetirement.Result.SENT -> "The wallet sent the exact transfer. Recipient credit is a separate observation."
        QuantumAuthorizationRetirement.Result.INCLUDED_WITHOUT_EXACT_TRANSFER -> "The request was included; the exact transfer was not confirmed (${record.reason}). This does not prove that no funds moved."
        QuantumAuthorizationRetirement.Result.EXPIRED_UNVERIFIED -> "Expired; payment outcome unverified."
    }
    private fun time(seconds: Long) = DateFormat.getDateTimeInstance().format(Date(seconds * 1000))
    private fun discardUnsigned() {
        val entry = pending ?: return
        if (entry.phase != QuantumPaymentOutbox.Phase.RESERVED) return
        AlertDialog.Builder(requireContext()).setTitle("Discard this unsigned request?")
            .setMessage("Only an authorization that has never reached signing can be discarded.")
            .setNegativeButton("Keep", null).setPositiveButton("Discard") { _, _ ->
                run("Discarding the unsigned request…") {
                    accounts.cancelUnsignedQuantumPayment(wallet, entry.id); pending = null; journalLoaded = true
                    details.text = ""; status.text = "Unsigned request discarded."
                }
            }.show()
    }
    private fun render() {
        if (!::review.isInitialized) return
        val idle = operation?.isActive != true
        val editing = idle && journalLoaded && pending == null && prepared == null && !ended
        for (field in listOf(recipient, amount, comment)) field.isEnabled = editing
        bounce.isEnabled = editing
        review.isEnabled = editing
        sign.isEnabled = idle && wallet.isQuantumSelfPayAvailable && prepared != null && pending == null
        submit.isEnabled = idle && wallet.isQuantumSelfPayAvailable && quote != null
        refreshQuote.isEnabled = idle && wallet.isQuantumSelfPayAvailable && quote != null
        recoverQuote.isEnabled = idle && !retiring && wallet.isQuantumSelfPayAvailable && quote == null && pending?.phase == QuantumPaymentOutbox.Phase.SIGNED
        verify.isEnabled = idle && pending?.signedBOC != null
        older.isEnabled = idle && cursor != null
        configureRpc.isEnabled = idle && !retiring && pending?.signedBOC != null
        discard.isEnabled = idle && !retiring && pending?.phase == QuantumPaymentOutbox.Phase.RESERVED
        endAuthorization.isEnabled = idle && journalLoaded && pending != null
        newPayment.isEnabled = idle && journalLoaded && pending == null && ended
    }
    private fun format(value: BigInteger) = checkNotNull(QuantumHistoryTransaction.formatNano(value.toString()))
    private fun tosInput(value: BigInteger) = format(value).removeSuffix(" TOS")
    private fun recipientAddress(intent: QuantumPaymentIntent) = intent.recipient.toString(
        userFriendly = true, bounceable = intent.bounce, testOnly = wallet.testnet,
    )
    private fun summary(intent: QuantumPaymentIntent) = "Recipient: ${recipientAddress(intent)}\nAmount: ${format(intent.amount)}\nBounce: ${if (intent.bounce) "yes" else "no"}\nComment: ${intent.comment ?: "(none)"}"
    companion object {
        fun newInstance(wallet: WalletEntity, recipient: String? = null, amountNano: BigInteger? = null, comment: String? = null) =
            QuantumPaymentScreen(wallet).apply {
                putStringArg("recipient", recipient); putStringArg("amount", amountNano?.let { QuantumHistoryTransaction.formatNano(it.toString())?.removeSuffix(" TOS") })
                putStringArg("comment", comment)
            }
    }
}
