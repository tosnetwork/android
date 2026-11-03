package network.tos.wallet.app.ui.screen.privacy

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import network.tos.wallet.app.R
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.privacy.PrivacyBalanceState
import network.tos.wallet.data.passcode.PasscodeManager
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.math.BigDecimal
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Separate privacy wallet and receipt screen. */
class PrivacyWalletScreen(wallet: WalletEntity) : WalletContextScreen(R.layout.fragment_pq_wallets, wallet) {
    override val fragmentName = "PrivacyWalletScreen"
    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()
    private val accounts: AccountRepository by inject()
    private val passcodes: PasscodeManager by inject()
    private lateinit var layout: LinearLayout
    private lateinit var amount: TextView
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private lateinit var receive: Button
    private lateinit var recipient: android.widget.EditText
    private lateinit var receipt: TextView
    private val foreground = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private var job: Job? = null
    private var authenticating = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(Color.rgb(29, 22, 45))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        layout = view.findViewById(R.id.pq_content)
        label("🔒 Privacy wallet", "privacy.title").textSize = 25f
        label(wallet.label.name, "privacy.wallet")
        label("Privacy assets are separate from your ordinary TOS balance.", "privacy.mode")
        amount = label("—", "privacy.balance").apply { textSize = 27f }
        status = label("Refresh to obtain a verified observation.", "privacy.status")
        refresh = button("Refresh privacy observation", "privacy.refresh") { refresh() }
        label("This reads through your configured TOS node. It does not use a Tor relayer or make all wallet traffic anonymous.", "privacy.route")
        receive = button("Private receive", "privacy.receive") { refresh(forReceipt = true) }
        receipt = label("", "privacy.receipt").apply { setTextIsSelectable(true); visibility = View.GONE }
        recipient = android.widget.EditText(requireContext()).apply {
            hint = "Private recipient descriptor"
            contentDescription = "privacy.recipient"
            isSaveEnabled = false
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            filters = arrayOf(android.text.InputFilter.LengthFilter(3432))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            this@PrivacyWalletScreen.layout.addView(this)
        }
        button("Check pending private payment", "privacy.pending") { refresh(forPending = true) }
        button("Check private recipient", "privacy.recipient.check") { validateRecipient() }
        for ((text, id) in listOf("Private transfer" to "send",
            "Deposit: ordinary wallet → privacy pool" to "deposit", "Withdraw: privacy pool → ordinary address" to "withdraw")) {
            button(text, "privacy.$id") {}.isEnabled = false
        }
        label("Private transfers, deposits and withdrawals are still in development.", "privacy.actions.unavailable")
        button("Return to ordinary wallet", "privacy.close") { finish() }
    }

    private fun label(text: String, id: String) = TextView(requireContext()).apply {
        this.text = text; contentDescription = id; textSize = 17f
        setTextColor(Color.rgb(238, 231, 249)); setPadding(0, 14, 0, 14); this@PrivacyWalletScreen.layout.addView(this)
    }
    private fun button(text: String, id: String, action: () -> Unit) = Button(requireContext()).apply {
        this.text = text; contentDescription = id; isAllCaps = false
        setOnClickListener { action() }; this@PrivacyWalletScreen.layout.addView(this)
    }
    private fun validateRecipient() {
        if (job?.isActive == true || !foreground.get()) return
        val input = recipient.text.toString()
        val tag = sequence.incrementAndGet()
        val stopped = { !foreground.get() || sequence.get() != tag }
        status.text = "Checking private recipient…"
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val valid = accounts.validatePrivacyRecipient(wallet, input, stopped)
                currentCoroutineContext().ensureActive()
                if (!stopped() && isAdded) {
                    status.text = when {
                        recipient.text.toString() != input -> "Recipient changed. Check it again."
                        valid -> "Recipient descriptor is valid for this pool. No payment submitted."
                        else -> "Invalid recipient descriptor for this pool."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!stopped() && isAdded) status.text = "Could not verify the recipient against the selected pool."
            }
        }
    }

    private fun refresh(forReceipt: Boolean = false, forPending: Boolean = false) {
        if (job?.isActive == true || !foreground.get()) return
        val tag = sequence.incrementAndGet()
        clearReceipt()
        show(PrivacyBalanceState.Loading)
        refresh.isEnabled = false
        receive.isEnabled = false
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val unavailable = accounts.privacyReadAvailability(wallet)
                currentCoroutineContext().ensureActive()
                if (!foreground.get() || sequence.get() != tag) return@launch
                if (unavailable != null) { show(unavailable); return@launch }
                val hasPin = passcodes.hasPinCode()
                currentCoroutineContext().ensureActive()
                if (!foreground.get() || sequence.get() != tag) return@launch
                if (!hasPin) {
                    status.text = "Set a wallet passcode before reading your private balance."
                    return@launch
                }
                authenticating = true
                val accepted = try { passcodes.confirmation(requireContext(), when {
                    forReceipt -> "Create private receipt"
                    forPending -> "Check pending private payment"
                    else -> "Read privacy wallet"
                }) }
                    finally { authenticating = false }
                currentCoroutineContext().ensureActive()
                if (!accepted || !foreground.get()) {
                    if (isAdded && sequence.get() == tag) { amount.text = "—"; status.text = "Authentication cancelled. No balance was published." }
                    return@launch
                }
                val stopped = { !foreground.get() || sequence.get() != tag }
                if (forPending) {
                    val pending = accounts.privacyPendingStatus(wallet, stopped)
                    currentCoroutineContext().ensureActive()
                    if (isAdded && !stopped()) status.text = when (pending.name) {
                        "NO_RECORD" -> "No pending private payment."
                        "UNOBSERVED" -> "The pool has not yet observed this payment. Its original request is retained."
                        "ACCEPTED" -> "The pool accepted this private payment."
                        else -> error("Unexpected privacy payment status")
                    }
                } else if (forReceipt) {
                    val descriptor = accounts.issuePrivacyReceipt(wallet, stopped)
                    try {
                        currentCoroutineContext().ensureActive()
                        if (isAdded && !stopped()) {
                            receipt.text = android.util.Base64.encodeToString(descriptor, android.util.Base64.NO_WRAP)
                            receipt.visibility = View.VISIBLE
                            status.text = "Private receiving descriptor. Share it with the sender."
                        }
                    } finally { descriptor.fill(0) }
                } else {
                    val result = accounts.readPrivacyBalance(wallet, stopped)
                    currentCoroutineContext().ensureActive()
                    if (isAdded && !stopped()) show(result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isAdded && foreground.get() && sequence.get() == tag) show(PrivacyBalanceState.Failed(PrivacyBalanceState.Failure.VERIFICATION_OR_TRANSPORT))
            } finally {
                if (isAdded && sequence.get() == tag) {
                    refresh.isEnabled = true
                    receive.isEnabled = true
                }
            }
        }
    }
    private fun show(state: PrivacyBalanceState) {
        amount.text = "—"
        status.text = when (state) {
            PrivacyBalanceState.Loading -> "Verifying pool and complete history…"
            PrivacyBalanceState.NotConfigured -> "Privacy is not configured for this wallet’s network. No balance has been verified."
            PrivacyBalanceState.MaterialUnavailable -> "Original TOS recovery material is unavailable or the device is locked. Restore the original wallet recovery material to read your private balance."
            PrivacyBalanceState.WalletNotProvisioned -> "Privacy reading has not been set up for this wallet. Your ordinary wallet has not changed."
            PrivacyBalanceState.Unverified -> "Observation is no longer current. Refresh to verify again."
            is PrivacyBalanceState.Failed -> if (state.reason == PrivacyBalanceState.Failure.BUSY)
                "Another privacy read is in progress. Try again after it finishes."
                else "Privacy balance could not be verified. Check your node and trusted pool configuration, then refresh. This is not a zero balance."
            is PrivacyBalanceState.Observed -> {
                amount.text = BigDecimal(BigInteger(state.summary.unspentBalance), 9).stripTrailingZeros().toPlainString() + " TOS observed"
                "Verified chain observation • network ${state.globalId}\nPool: ${state.pool}\n" +
                    "Proven at ${DateFormat.getDateTimeInstance().format(Date(state.provenShardTime * 1000))}.\n" +
                    "Deployment pins were explicitly selected locally; the chain proof checks that selection.\n" +
                    "This is not a spending limit: local pending reservations have not been deducted."
            }
        }
    }
    private fun clearReceipt() {
        if (::receipt.isInitialized) {
            receipt.text = ""
            receipt.visibility = View.GONE
        }
    }
    private fun leaveForeground() {
        clearReceipt()
        if (::recipient.isInitialized) recipient.text.clear()
        foreground.set(false); sequence.incrementAndGet(); job?.cancel(); job = null
        if (::amount.isInitialized) { show(PrivacyBalanceState.Unverified); refresh.isEnabled = true; receive.isEnabled = true }
    }
    override fun onResume() { super.onResume(); foreground.set(true) }
    override fun onPause() {
        if (authenticating) foreground.set(false) else leaveForeground()
        super.onPause()
    }
    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) {
            if (authenticating) foreground.set(false) else leaveForeground()
        } else foreground.set(true)
        super.onHiddenChanged(hidden)
    }
    override fun onDestroyView() { leaveForeground(); super.onDestroyView() }
    companion object { fun newInstance(wallet: WalletEntity) = PrivacyWalletScreen(wallet) }
}
