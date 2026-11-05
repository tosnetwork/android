package network.tos.wallet.app.ui.screen.privacy

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import network.tos.wallet.app.extensions.copyToClipboard
import network.tos.wallet.app.BuildConfig
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
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Separate privacy wallet and receipt screen. */
class PrivacyWalletScreen(wallet: WalletEntity, private val payment: PaymentConfiguration? = null) : WalletContextScreen(R.layout.fragment_pq_wallets, wallet) {
    data class PaymentConfiguration(val phase1: java.io.File, val ceremony: java.io.File, val approvedRelay: ByteArray, val approvedRecoveryRelay: ByteArray, val pool: network.tos.wallet.data.account.privacy.PrivacyPoolSelection? = null)

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
    private lateinit var copyReceipt: Button
    private lateinit var receipt: TextView
    private val foreground = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private var job: Job? = null
    private var authenticating = false
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let { uri -> transferBackup(uri, restore = false) }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> transferBackup(uri, restore = true) }
    }

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
        copyReceipt = button("Copy private receiving descriptor", "privacy.receipt.copy") {
            val descriptor = receipt.text.toString()
            if (descriptor.isNotEmpty()) {
                requireContext().copyToClipboard(descriptor, sensitive = true)
                status.text = "Private receiving descriptor copied."
            }
        }.apply { visibility = View.GONE }
        receipt = label("", "privacy.receipt").apply { setTextIsSelectable(true); visibility = View.GONE }
        recipient = android.widget.EditText(requireContext()).apply {
            hint = "Private descriptor or ordinary TOS address"
            contentDescription = "privacy.recipient"
            isSaveEnabled = false
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            filters = arrayOf(android.text.InputFilter.LengthFilter(3432))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            this@PrivacyWalletScreen.layout.addView(this)
        }
        payment?.let { configuration ->
            button("Send privately through Tor", "privacy.send") { startPayment(configuration) }
            button("Retry pending payment through Tor", "privacy.retry") { retryPayment(configuration) }
        }
        button("Check pending private payment", "privacy.pending") { refresh(forPending = true) }
        button("Export encrypted privacy backup", "privacy.backup.export") {
            if (job?.isActive != true) exportBackup.launch("tos-privacy-backup.bin")
        }
        button("Restore encrypted privacy backup", "privacy.backup.restore") {
            if (job?.isActive != true) importBackup.launch(arrayOf("application/octet-stream"))
        }
        button("Check private recipient", "privacy.recipient.check") { validateRecipient() }
        if (payment == null) button("Private transfer", "privacy.send") {}.isEnabled = false
        button("Deposit: ordinary wallet → privacy pool", "privacy.deposit") { prepareDeposit() }
        button("Withdraw through Tor: privacy pool → ordinary address", "privacy.withdraw") {
            payment?.let { startPayment(it, withdrawal = true) }
        }.isEnabled = payment != null
        label(
            if (payment == null) "Private transfers and withdrawals require a configured Tor relay."
            else "Private transfers and withdrawals use the configured Tor relayer. Withdrawal recipients and amounts are public on chain.",
            "privacy.actions.unavailable",
        )
        button("Return to ordinary wallet", "privacy.close") { finish() }
    }

    private fun label(text: String, id: String) = TextView(requireContext()).apply {
        this.text = text; contentDescription = id; textSize = 17f
        setTextColor(Color.rgb(238, 231, 249)); setPadding(0, 14, 0, 14); this@PrivacyWalletScreen.layout.addView(this)
    }

    private fun transferBackup(uri: Uri, restore: Boolean) {
        if (job?.isActive == true || !isAdded) return
        val context = requireContext().applicationContext
        val tag = sequence.incrementAndGet()
        val stopped = { !foreground.get() || sequence.get() != tag }
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                authenticating = true
                val approved = try {
                    passcodes.confirmation(requireContext(), if (restore) "Restore privacy backup" else "Export privacy backup")
                } finally { authenticating = false }
                if (!approved || stopped()) return@launch
                amount.text = "—"
                status.text = if (restore) "Validating and restoring encrypted backup…" else "Preparing encrypted privacy backup…"
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("privacy-backup-", ".bin", context.cacheDir)
                    try {
                        if (restore) {
                            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    var total = 0
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= 50 * 1024 * 1024) { "Privacy backup is too large" }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        val mode = if (restore) ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE
                        ParcelFileDescriptor.open(file, mode).use { descriptor ->
                            accounts.transferPrivacyBackup(wallet, descriptor.fd, restore, stopped)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!restore) {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                                file.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                    } finally { file.delete() }
                }
                if (!stopped()) {
                    if (restore) show(accounts.readPrivacyBalance(wallet, stopped))
                    else status.text = "Encrypted privacy backup saved."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!stopped()) status.text = if (restore)
                    "Restore could not be verified. Check the saved wallet state before retrying. Restore requires the original recovery material and a fresh privacy wallet."
                    else "Encrypted privacy backup could not be saved."
            }
        }
    }
    private fun button(text: String, id: String, action: () -> Unit) = Button(requireContext()).apply {
        this.text = text; contentDescription = id; isAllCaps = false
        setOnClickListener { action() }; this@PrivacyWalletScreen.layout.addView(this)
    }
    private fun prepareDeposit() {
        if (job?.isActive == true || !foreground.get()) return
        val amountInput = android.widget.EditText(requireContext()).apply {
            hint = "Amount in nanoTOS"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
        }
        val tag = sequence.incrementAndGet()
        val stopped = { !foreground.get() || sequence.get() != tag }
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = confirmPayment("Deposit ordinary TOS", null, amountInput) ?: return@launch
                val amount = text.toULongOrNull()
                if (amount == null || amount == 0uL || amount.toString() != text) {
                    status.text = "Enter a positive deposit amount in nanoTOS."
                    return@launch
                }
                if (!passcodes.hasPinCode() || stopped()) return@launch
                authenticating = true
                val allowed = try { passcodes.confirmation(requireContext(), "Review private deposit") }
                    finally { authenticating = false }
                if (!allowed || stopped()) return@launch
                status.text = "Verifying the privacy pool and deposit fee…"
                payment?.pool?.let { accounts.configureTrustedPrivacyPool(wallet, it) }
                val value = accounts.privacyDepositValue(wallet, amount, stopped)
                val queryId = java.security.SecureRandom().nextLong().toULong()
                val intent = accounts.preparePrivacySelfDeposit(wallet, amount, value, queryId, stopped)
                currentCoroutineContext().ensureActive()
                if (!stopped()) {
                    status.text = "Review the deposit in the ordinary payment screen."
                    navigation?.add(network.tos.wallet.app.ui.screen.pq.QuantumPaymentScreen.newDepositInstance(wallet, intent))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!stopped()) status.text = "Unable to prepare the deposit. Check the amount and your configured TOS node."
            }
        }
    }

    private fun startPayment(configuration: PaymentConfiguration, withdrawal: Boolean = false) {
        if (job?.isActive == true || !foreground.get()) return
        val input = recipient.text.toString().trim()
        val ordinaryAddress = if (withdrawal) runCatching {
            PrivacyWithdrawalRecipient.parse(input, wallet.testnet)
        }.getOrNull() else null
        val destination = if (withdrawal) ordinaryAddress?.let { address ->
            ("0:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }).encodeToByteArray()
        } else runCatching { android.util.Base64.decode(input, android.util.Base64.NO_WRAP) }.getOrNull()
        val validDestination = if (withdrawal) ordinaryAddress != null else
            destination?.size == 2573 && android.util.Base64.encodeToString(destination, android.util.Base64.NO_WRAP) == input
        if (!validDestination || destination == null) {
            status.text = if (withdrawal) "Enter an ordinary TOS address in workchain 0 for the selected network." else "Enter a valid private recipient descriptor."
            return
        }
        val amountInput = android.widget.EditText(requireContext()).apply {
            hint = "Amount in nanoTOS"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
        }
        val tag = sequence.incrementAndGet()
        val stopped = { !foreground.get() || sequence.get() != tag }
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val value = confirmPayment("Private payment amount", null, amountInput) ?: return@launch
                val amount = value.toULongOrNull()
                if (amount == null || amount == 0uL || amount.toString() != value) {
                    status.text = "Enter a positive amount in nanoTOS."
                    return@launch
                }
                if (!passcodes.hasPinCode() || stopped()) return@launch
                authenticating = true
                val allowed = try { passcodes.confirmation(requireContext(), "Authorize private payment") }
                    finally { authenticating = false }
                if (!allowed || stopped()) return@launch
                status.text = "Verifying recipient and loading the payment prover…"
                configuration.pool?.let { accounts.configureTrustedPrivacyPool(wallet, it) }
                if (!withdrawal) check(accounts.validatePrivacyRecipient(wallet, input, stopped))
                val poolFee = if (withdrawal) accounts.privacyWithdrawalFee(wallet, stopped) else null
                if (configuration.pool != null) {
                    val context = requireContext().applicationContext
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        installBundledMaterial(context, configuration.phase1.parentFile!!)
                    }
                }
                accounts.loadPrivacyPaymentProver(wallet, configuration.phase1, configuration.ceremony, stopped).use { prover ->
                    status.text = "Requesting a relay quote through Tor…"
                    accounts.requestPrivacyRelayOffer(wallet, configuration.approvedRelay, stopped).use { offer ->
                        val details = offer.details()
                        val direction = if (withdrawal) "Privacy pool → ordinary address\nNetwork ID ${checkNotNull(wallet.quantumIdentity).wallet.initialAuthority.globalId}\n" else ""
                        val confirmed = confirmPayment(if (withdrawal) "Confirm Tor withdrawal" else "Confirm private payment",
                            direction + "Amount ${amount} nanoTOS\nRelay fee ${details.fee} nanoTOS\nRoute Tor\nRecipient ${if (withdrawal) input else input.take(20) + "…" + input.takeLast(20)}" +
                                (poolFee?.let { "\nPool fee $it nanoTOS\nRecipient and withdrawal amount are public on chain. Tor hides your connection address from the relay; it does not make the payout untraceable." } ?: ""))
                        if (confirmed == null || stopped()) return@launch
                        status.text = "Preparing and submitting the private payment through Tor…"
                        accounts.stagePrivacyPayment(wallet, destination, value, details.fee, details.identity, prover, offer, withdrawalFee = poolFee, stopped = stopped)
                            .use { accounts.submitOrRetryPrivacyPayment(it, stopped) }
                        if (!stopped()) status.text = "Relay responded. Check pending payment to verify chain confirmation."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!stopped()) status.text = "Unable to complete private payment through Tor. Check pending payment before trying again."
            }
        }
    }

    private fun retryPayment(configuration: PaymentConfiguration) {
        if (job?.isActive == true || !foreground.get()) return
        val tag = sequence.incrementAndGet()
        val stopped = { !foreground.get() || sequence.get() != tag }
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!passcodes.hasPinCode() || stopped()) return@launch
                authenticating = true
                val allowed = try { passcodes.confirmation(requireContext(), "Retry pending private payment") }
                    finally { authenticating = false }
                if (!allowed || stopped()) return@launch
                status.text = "Recovering the original private payment…"
                val payment = accounts.recoverPrivacyPayment(wallet, configuration.approvedRecoveryRelay, stopped)
                if (payment == null) {
                    if (!stopped()) status.text = "No unresolved payment. Refresh to verify your balance."
                    return@launch
                }
                payment.use {
                    if (stopped()) return@launch
                    status.text = "Checking the original request through Tor…"
                    accounts.submitOrRetryPrivacyPayment(it, stopped)
                }
                if (!stopped()) status.text = "Relay responded. Check pending payment to verify chain confirmation."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!stopped()) status.text = "Unable to resolve the pending payment through Tor."
            }
        }
    }

    private suspend fun confirmPayment(
        title: String, message: String?, input: android.widget.EditText? = null,
    ): String? = suspendCancellableCoroutine { continuation ->
        val dialog = android.app.AlertDialog.Builder(requireContext()).setTitle(title)
            .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(null) }
            .setPositiveButton("Continue") { _, _ ->
                if (continuation.isActive) continuation.resume(input?.text?.toString() ?: "")
            }
        if (input != null) dialog.setView(input)
        if (message != null) dialog.setMessage(message)
        val shown = dialog.create()
        shown.setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
        continuation.invokeOnCancellation { view?.post { shown.dismiss() } }
        shown.show()
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
                if (accounts.privacyReadAvailability(wallet) == PrivacyBalanceState.NotConfigured) {
                    payment?.pool?.let { accounts.configureTrustedPrivacyPool(wallet, it) }
                }
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
                            copyReceipt.visibility = View.VISIBLE
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
            copyReceipt.visibility = View.GONE
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
    companion object {
        fun newInstance(wallet: WalletEntity, context: android.content.Context) =
            PrivacyWalletScreen(wallet, simulatorFixture(context) ?: bundledRelease(context))

        private fun bundledRelease(context: android.content.Context): PaymentConfiguration? = runCatching {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val directory = java.io.File(context.noBackupFilesDir, "PrivacyPayment-$version")
            val phase1 = directory.resolve("phase1")
            val ceremony = directory.resolve("ceremony")
            fun readConfig(name: String) = context.assets.open("PrivacyPayment/$name").use { stream ->
                val limit = 256 * 1024
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(buffer.size() + count <= limit)
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            val fields = org.json.JSONObject(readConfig("pool.json").decodeToString())
            val profile = network.tos.wallet.data.account.pq.QuantumNetworkProfile(
                fields.getString("global_id").toInt(), fields.getString("root_hash"), fields.getString("file_hash")
            )
            val pool = network.tos.wallet.data.account.privacy.PrivacyPoolSelection(
                profile, "0:${fields.getString("pool_account")}",
                android.util.Base64.decode(fields.getString("code_boc"), android.util.Base64.NO_WRAP),
                fields.getString("profile_hash"), fields.getString("poseidon_manifest_hash"),
                fields.getString("groth16_vk_hash")
            )
            val offer = readConfig("relay.json")
            val recovery = org.json.JSONObject(offer.decodeToString()).apply { remove("lifetime") }
            PaymentConfiguration(phase1, ceremony, offer, recovery.toString().encodeToByteArray(), pool = pool)
        }.getOrNull()

        @Synchronized
        private fun installBundledMaterial(context: android.content.Context, destination: java.io.File) {
            if (destination.isDirectory) return
            val staging = context.noBackupFilesDir.resolve("privacy-payment-${java.util.UUID.randomUUID()}")
            check(staging.mkdir())
            try {
                fun copyAsset(path: String, target: java.io.File) {
                    val children = context.assets.list(path).orEmpty()
                    if (children.isEmpty()) {
                        context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
                    } else {
                        check(target.mkdir())
                        children.forEach { name -> copyAsset("$path/$name", target.resolve(name)) }
                    }
                }
                for (name in listOf("phase1", "ceremony")) {
                    copyAsset("PrivacyPayment/$name", staging.resolve(name))
                    check(staging.resolve(name).isDirectory)
                }
                check(staging.renameTo(destination))
            } finally {
                staging.deleteRecursively()
            }
        }

        private fun simulatorFixture(context: android.content.Context): PaymentConfiguration? {
            if (!BuildConfig.DEBUG) return null
            return runCatching {
                val fixture = java.io.File(context.filesDir, "tos-privacy-payment-fixture.json")
                require(fixture.length() in 1..(256L * 1024L))
                val fields = org.json.JSONObject(fixture.readText())
                val phase1 = java.io.File(fields.getString("phase1"))
                val ceremony = java.io.File(fields.getString("ceremony"))
                require(phase1.isAbsolute && ceremony.isAbsolute)
                val relay = fields.getJSONObject("relay")
                val offer = relay.toString().encodeToByteArray()
                relay.remove("lifetime")
                PaymentConfiguration(phase1, ceremony, offer, relay.toString().encodeToByteArray())
            }.getOrNull()
        }
    }
}
