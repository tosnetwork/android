package network.tos.wallet.app.ui.screen.pq

import android.content.Intent
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.ton.cell.buildCell
import kotlinx.coroutines.withContext
import network.tos.blockchain.ton.contract.TosPqRelay
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.extensions.base64
import network.tos.security.pq.PqAlgorithm
import network.tos.security.pq.PqVerifier
import network.tos.wallet.app.R
import network.tos.wallet.api.API
import network.tos.wallet.api.tos.TosPqFeeSnapshot
import network.tos.wallet.api.tos.TosPqSnapshot
import network.tos.wallet.api.tos.TosPqReceipt
import network.tos.wallet.api.tos.pqReconcile
import network.tos.wallet.data.account.pq.PqOperationJournal
import network.tos.wallet.app.usecase.sign.SignUseCase
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.PqWalletRecord
import network.tos.wallet.data.account.pq.PqWalletRepository
import network.tos.wallet.data.passcode.PasscodeManager
import org.koin.android.ext.android.inject
import org.ton.block.AddrStd
import uikit.base.BaseFragment
import uikit.navigation.NavigationActivity
import uikit.navigation.Navigation.Companion.navigation
import java.math.BigDecimal

/** Explicit PQ identities, with one selected native wallet paying transport fees. */
class PqWalletsScreen(private val feeWallet: WalletEntity) : BaseFragment(R.layout.fragment_pq_wallets), BaseFragment.SwipeBack {
    override val fragmentName = "PqWalletsScreen"
    private val api: API by inject()
    private val nodeSource by lazy { api.tos.snapshot(feeWallet.testnet) }
    private val passcodes: PasscodeManager by inject()
    private val signer: SignUseCase by inject()
    private lateinit var layout: LinearLayout
    private val journal by lazy { PqOperationJournal(requireContext()) }
    private var busy = false
    private var previousSecure = false
    private val repository by lazy {
        PqWalletRepository(requireContext()) { passcodes.confirmation(requireActivity() as NavigationActivity, "PQ wallet") }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layout = view.findViewById(R.id.pq_content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        refresh()
    }
    override fun onResume() {
        super.onResume()
        previousSecure = requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onPause() {
        if (!previousSecure) requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }
    private fun label(value: String) { layout.addView(TextView(requireContext()).apply { text = value; textSize = 17f; setPadding(0, 12, 0, 12) }) }
    private fun button(title: String, id: String, action: () -> Unit) {
        layout.addView(Button(requireContext()).apply { text = title; isAllCaps = false; contentDescription = id; setOnClickListener { if (!busy) action() } })
    }
    private fun refresh() {
        layout.removeAllViews()
        label("PQ Wallets")
        button("V5R2 accounts", "pq.v5r2") { navigation?.add(V5R2WalletsScreen()) }
        label("Fee wallet: ${feeWallet.label.name}\n${feeWallet.address}")
        button("Create PQ wallet", "pq.create") { chooseAlgorithm(false) }
        button("Restore encrypted backup", "pq.restore") { chooseAlgorithm(true) }
        button("Cryptography licenses", "pq.notices") { share(requireContext().assets.open("tos-pq-notices.txt").bufferedReader().use { it.readText() }) }
        try {
            for (record in repository.list()) {
                label("${record.name} · ${record.algorithm.name}")
                button(raw(record.descriptor().address), "pq.wallet.${record.id}") { detail(record) }
            }
        } catch (e: Exception) { error() }
    }
    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { action() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("TosPq", "Operation failed: ${e.javaClass.simpleName}; ${e.stackTrace.take(6).joinToString()}"); error() } finally { busy = false }
        }
    }
    private fun error() { if (isAdded) AlertDialog.Builder(requireContext()).setTitle("PQ operation unavailable")
        .setMessage("Check the selected node, network, device unlock and backup password. No fallback signature was used.")
        .setPositiveButton("OK", null).show() }
    private fun chooseAlgorithm(restore: Boolean) {
        AlertDialog.Builder(requireContext()).setTitle(if (restore) "Restore wallet" else "Create wallet")
            .setItems(arrayOf("ML-DSA-44", "Falcon-512 padded")) { _, i -> create(PqAlgorithm.entries[i], restore) }.show()
    }
    private fun fields(title: String, names: List<String>, passwords: Set<Int> = emptySet(), action: (List<String>) -> Unit) {
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 32, 0) }
        val inputs = names.mapIndexed { index, name -> EditText(requireContext()).apply {
            hint = name; contentDescription = "pq.input.$index"; maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or if (index in passwords) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            body.addView(this)
        } }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(title).setView(body)
            .setPositiveButton("Continue") { _, _ -> val values = inputs.map { it.text.toString() }; inputs.forEach { it.text.clear() }; action(values) }
            .setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { inputs.forEach { it.text.clear() } }
        dialog.show()
    }
    private fun create(algorithm: PqAlgorithm, restore: Boolean) {
        fields(if (restore) "Restore encrypted PQ backup" else "Create ${algorithm.name}",
            if (restore) listOf("Wallet name", "Encrypted backup (Base64)", "Backup password") else listOf("Wallet name"),
            if (restore) setOf(2) else emptySet()) { values -> run {
                val network = withContext(Dispatchers.IO) { nodeSource.getNetworkInfo(feeWallet.testnet) }
                check(network.vmVersion >= algorithm.minimumVm) { "Profile is not activated" }
                withContext(Dispatchers.IO) {
                    if (restore) {
                        val password = values[2].toCharArray()
                        try { repository.restore(values[0], algorithm, network.globalId,
                            android.util.Base64.decode(values[1], android.util.Base64.NO_WRAP), password) } finally { password.fill('\u0000') }
                    } else repository.create(values[0], algorithm, network.globalId)
                }
                refresh()
            } }
    }
    private fun detail(record: PqWalletRecord) {
        val wallet = record.descriptor()
        layout.removeAllViews();label(record.name);label(raw(wallet.address))
        journal.read(raw(wallet.address))?.let { label("Last submission: ${it.status}. Refresh balance and history to check the exact receipt.") }
        button("Back to PQ wallets", "pq.back") { refresh() }
        button("Receive / share address", "pq.receive") { share(raw(wallet.address)) }
        button("Refresh balance and history", "pq.history") { run {
            reconcile(record)
            val node = nodeSource
            val account = withContext(Dispatchers.IO) { node.getAccountState(raw(wallet.address), feeWallet.testnet) }
            label("Balance: ${BigDecimal(account.balance).movePointLeft(9).toPlainString()} TOS · ${account.status}")
            if (account.lastTransactionId != null) {
                val history = withContext(Dispatchers.IO) { node.getTransactions(raw(wallet.address), testnet = feeWallet.testnet) }
                history.forEach { label("${java.util.Date(it.utime * 1000)}\n${it.hash}") }
            }
        } }
        button("Deploy / fund wallet", "pq.deploy") { deploy(record) }
        button("Send TOS", "pq.send") { send(record) }
        button("Export encrypted backup", "pq.backup") { fields("Encrypt backup", listOf("Password (at least 12 characters)", "Confirm password"), setOf(0, 1)) { values -> run {
            check(values[0] == values[1]); val password = values[0].toCharArray()
            val recordBytes = try { withContext(Dispatchers.IO) { repository.backup(record.id, password) } } finally { password.fill('\u0000') }
            share(android.util.Base64.encodeToString(recordBytes, android.util.Base64.NO_WRAP))
        } } }
        button("Delete local key", "pq.delete") {
            AlertDialog.Builder(requireContext()).setTitle("Delete ${record.name}?")
                .setMessage("Funds remain on chain. You need the encrypted PQ backup and its password to restore this wallet.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> run { withContext(Dispatchers.IO) { repository.delete(record.id) }; refresh() } }.show()
        }
    }
    private fun amount(raw: String): Long {
        require(raw.matches(Regex("[0-9]+(\\.[0-9]{1,9})?")))
        return BigDecimal(raw).movePointRight(9).longValueExact().also { require(it > 0) }
    }
    private fun deploy(record: PqWalletRecord) {
        fields("Deploy ${record.name}", listOf("Verifier funding (TOS)", "PQ wallet funding (TOS)")) { values ->
            val root = runCatching { amount(values[0]) }.getOrNull();val funding = runCatching { amount(values[1]) }.getOrNull()
            if (root == null || funding == null) { error(); return@fields }
            confirm("Deploy ${record.name}", "Fee wallet pays ${values[0]} TOS to its verifier and ${values[1]} TOS to ${raw(record.descriptor().address)}, plus network fees. The verifier deposit is reserved for storage and cannot be withdrawn.") {
                run { val relay = TosPqRelay(record.descriptor()); transport(record, relay, relay.deployment(root, funding), Math.addExact(root, funding)) }
            }
        }
    }
    private fun send(record: PqWalletRecord) {
        fields("Send from ${record.name}", listOf("Recipient address", "Amount (TOS)", "Comment", "Fee transport funding (TOS)")) { values ->
            val to = runCatching { AddrStd.parse(values[0]) }.getOrNull();val coins = runCatching { amount(values[1]) }.getOrNull()
            val funding = runCatching { amount(values[3]) }.getOrNull()
            if (to == null || coins == null || funding == null) { error();return@fields }
            confirm("Confirm PQ transfer", "${values[1]} TOS to ${raw(to)}\nComment: ${values[2]}\nFee wallet pays ${values[3]} TOS to the verifier, plus network fees.") {
                run {
                    ensureResolved(record)
                    val wallet = record.descriptor()
                    val snapshot = withContext(Dispatchers.IO) { TosPqSnapshot.read(nodeSource, wallet, feeWallet.testnet) }
                    check(snapshot.balance > java.math.BigInteger.valueOf(coins))
                    val request = wallet.request(snapshot.epoch, snapshot.nonce, snapshot.chainTime + 600, snapshot.chainTime,
                        wallet.transferPayload(to, coins, values[2]))
                    val signature = withContext(Dispatchers.IO) { repository.sign(record.id, wallet.signingMessage(request)) }
                    val fresh = withContext(Dispatchers.IO) { TosPqSnapshot.read(nodeSource, wallet, feeWallet.testnet) }
                    check(fresh.epoch == snapshot.epoch && fresh.nonce == snapshot.nonce)
                    val relay = TosPqRelay(wallet)
                    val plan = relay.submission(request, signature, funding) { message, proof -> PqVerifier.verify(record.algorithm, record.publicKey, message, proof) }
                    transport(record, relay, plan, funding, snapshot, raw(to), coins)
                }
            }
        }
    }
    private suspend fun transport(record: PqWalletRecord, relay: TosPqRelay, plan: TosPqRelay.Plan, funding: Long, expected: TosPqSnapshot? = null, recipient: String? = null, amount: Long = 0) {
        ensureResolved(record)
        check(feeWallet.hasPrivateKey)
        val contract = feeWallet.contract as? TosWalletV5R1Contract ?: error("Select a native TOS fee wallet")
        check(contract.networkGlobalId == record.network)
        val source = nodeSource
        val state = withContext(Dispatchers.IO) { TosPqFeeSnapshot.read(source, contract, record.algorithm.minimumVm, feeWallet.testnet) }
        val estimateBody = buildCell { storeSlice(unsignedPlaceholder(relay, plan, record, state).beginParse()); storeBytes(ByteArray(64)) }
        val fees = withContext(Dispatchers.IO) { source.estimateFee(raw(contract.address), estimateBody.base64(),
            if (state.seqno == 0) contract.getCode().base64() else null,
            if (state.seqno == 0) contract.getStateCell().base64() else null, feeWallet.testnet).total }
        val cost = java.math.BigInteger.valueOf(funding) + java.math.BigInteger.valueOf(fees)
        check(state.balance > cost)
        check(reviewFees(funding, fees)) { "Fee review canceled" }
        val unsigned = relay.feeSigningMessage(plan, record.network, state.seqno, state.chainTime, state.chainTime + 600)
        val signature = org.ton.bitstring.BitString(signer(requireActivity(), feeWallet, unsigned.hash().toByteArray()))
        val fresh = withContext(Dispatchers.IO) { TosPqFeeSnapshot.read(source, contract, record.algorithm.minimumVm, feeWallet.testnet) }
        check(fresh.seqno == state.seqno && fresh.chainTime < state.chainTime + 600)
        if (expected != null) {
            val current = withContext(Dispatchers.IO) { TosPqSnapshot.read(source, record.descriptor(), feeWallet.testnet) }
            check(current.epoch == expected.epoch && current.nonce == expected.nonce && current.chainTime < expected.chainTime + 600) { "PQ authority changed during authentication" }
        }
        val external = contract.createTransferMessageCell(contract.address, state.seqno, contract.signedBody(signature, unsigned))
        val boc = external.base64()
        journal.write(PqOperationJournal.Entry(TosPqReceipt.Intent(raw(contract.address), android.util.Base64.encodeToString(external.hash().toByteArray(), android.util.Base64.NO_WRAP),
            raw(record.descriptor().moduleAddress), raw(record.descriptor().address), recipient, amount), state.seqno, state.chainTime + 600, TosPqReceipt.Status.PENDING))
        withContext(Dispatchers.IO) { source.sendBocForNetwork(boc, record.network, feeWallet.testnet) }
        label("Submitted. Awaiting chain confirmation; check balance and history before retrying.")
    }
    private suspend fun ensureResolved(record: PqWalletRecord) {
        val existing = journal.read(raw(record.descriptor().address)) ?: return
        if (!existing.terminal) reconcile(record)
        check(journal.read(raw(record.descriptor().address))?.terminal == true) { "A previous submission is still unresolved" }
    }
    private suspend fun reconcile(record: PqWalletRecord) {
        val existing = journal.read(raw(record.descriptor().address)) ?: return
        val status = withContext(Dispatchers.IO) {
            val network = nodeSource.getNetworkInfo(feeWallet.testnet)
            check(network.globalId == record.network && network.vmVersion >= record.algorithm.minimumVm)
            val receipt = nodeSource.pqReconcile(existing.intent, feeWallet.testnet) {
                TosPqSnapshot.read(nodeSource, record.descriptor(), feeWallet.testnet);true
            }
            if (receipt == TosPqReceipt.Status.PENDING && existing.intent.feeAddress == raw(feeWallet.contract.address)) {
                val payer = TosPqFeeSnapshot.read(nodeSource, feeWallet.contract as TosWalletV5R1Contract, record.algorithm.minimumVm, feeWallet.testnet)
                if (payer.seqno == existing.seqno && payer.chainTime > existing.expires) TosPqReceipt.Status.EXPIRED else receipt
            } else receipt
        }
        journal.write(existing.copy(status = status));label("Submission: $status (selected-node receipts).")
    }
    private fun unsignedPlaceholder(relay: TosPqRelay, plan: TosPqRelay.Plan, record: PqWalletRecord, state: TosPqFeeSnapshot) =
        relay.feeSigningMessage(plan, record.network, state.seqno, state.chainTime, state.chainTime + 600)
    private suspend fun reviewFees(funding: Long, fees: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(requireContext()).setTitle("Review network fees")
            .setMessage("Fee wallet funding: ${BigDecimal(funding).movePointLeft(9)} TOS\nEstimated fee for fee-wallet submission: ${BigDecimal(fees).movePointLeft(9)} TOS. Verifier and PQ-wallet execution fees are paid from the funding and balances. Unused transfer funding is credited to the PQ wallet. Actual network fees may vary.")
            .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setPositiveButton("Confirm") { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
    private fun confirm(title: String, text: String, action: () -> Unit) { AlertDialog.Builder(requireContext()).setTitle(title).setMessage(text)
        .setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.show() }
    private fun share(text: String) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")) }
    private fun raw(a: AddrStd) = "${a.workchainId}:${a.address.toByteArray().joinToString("") { "%02x".format(it) }}"
}
