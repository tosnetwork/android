package network.tos.wallet.app.ui.screen.events.quantum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.QuantumHistoryReader
import network.tos.wallet.data.account.pq.QuantumHistoryTransaction
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import ui.theme.UIKit
import java.text.DateFormat
import java.util.Date

/** Actual Quantum product route. It never instantiates the ordinary event VM. */
class QuantumHistoryScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet) {
    private val coinDetails: Boolean get() = arguments?.getBoolean("quantum.coinDetails") ?: false
    override val fragmentName = "QuantumHistoryScreen"
    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()
    private val accounts: AccountRepository by inject()
    private var visible by mutableStateOf(false)
    override fun onResume() { super.onResume(); visible = !isHidden }
    override fun onPause() { visible = false; super.onPause() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); visible = !hidden && isResumed }

    companion object {
        fun newInstance(wallet: WalletEntity, coinDetails: Boolean = false) = QuantumHistoryScreen(wallet).apply {
            putBooleanArg("quantum.coinDetails", coinDetails)
        }
    }
    @Composable
    override fun ScreenContent() {
        QuantumHistoryContent(wallet, visible, coinDetails, { finish() }, {
            navigation?.add(network.tos.wallet.app.ui.screen.pq.QuantumPaymentScreen.newInstance(wallet))
        }) { cursor ->
            accounts.readQuantumHistory(wallet, cursor)
        }
    }
}

private data class PageRequest(val index: Int, val cursor: QuantumHistoryReader.Cursor?, val reset: Boolean, val serial: Long)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuantumHistoryContent(wallet: WalletEntity, visible: Boolean, coinDetails: Boolean, onClose: () -> Unit, onPayment: () -> Unit,
                                  read: suspend (QuantumHistoryReader.Cursor?) -> QuantumHistoryReader.Page) {
    var request by remember(wallet.id) { mutableStateOf(PageRequest(0, null, true, 0)) }
    var records by remember(wallet.id) { mutableStateOf<List<QuantumHistoryTransaction>>(emptyList()) }
    var starts by remember(wallet.id) { mutableStateOf<List<QuantumHistoryReader.Cursor?>>(listOf(null)) }
    var pageIndex by remember(wallet.id) { mutableStateOf(0) }
    var next by remember(wallet.id) { mutableStateOf<QuantumHistoryReader.Cursor?>(null) }
    var status by remember(wallet.id) { mutableStateOf("Verifying history…") }
    var balance by remember(wallet.id) { mutableStateOf<String?>(null) }
    var loading by remember(wallet.id) { mutableStateOf(true) }
    var detail by remember(wallet.id) { mutableStateOf<QuantumHistoryTransaction?>(null) }
    var operationID by remember(wallet.id) { mutableStateOf(0L) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LaunchedEffect(wallet.id, visible, request) {
        val id = operationID + 1; operationID = id
        val current = request
        records = emptyList(); balance = null; detail = null
        if (!visible) { loading = false; return@LaunchedEffect }
        loading = true; status = "Verifying history…"
        try {
            var loaded: QuantumHistoryReader.Page? = null
            for (attempt in 0..2) {
                try { loaded = read(current.cursor); break }
                catch (busy: QuantumHistoryReader.Busy) {
                    if (attempt == 2) throw busy
                    delay(300)
                }
            }
            val page = checkNotNull(loaded)
            if (operationID != id) return@LaunchedEffect
            if (current.reset) { starts = listOf(null); pageIndex = 0 }
            else {
                if (current.index == starts.size) starts = starts + current.cursor
                pageIndex = current.index
                if (starts.size > 128) { starts = starts.drop(1); pageIndex -= 1 }
            }
            records = page.transactions; next = page.next
            balance = QuantumHistoryTransaction.formatNano(page.balanceNano)
            val checkpoint = date.format(Date(page.checkpointTime * 1000))
            status = if (records.isEmpty()) "No transactions. Verified through $checkpoint." else "Verified through $checkpoint"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (operationID != id) return@LaunchedEffect
            records = emptyList(); balance = null; starts = listOf(null); pageIndex = 0; next = null
            status = "Unable to verify history. Refresh to try again."
        } finally { if (operationID == id) loading = false }
    }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(UIKit.colorScheme.background.page).statusBarsPadding().padding(horizontal = 16.dp)) {
        if (coinDetails) TextButton(onClick = onClose) { Text("Back", color = UIKit.colorScheme.text.accent) }
        TextButton(onClick = onPayment, modifier = Modifier.testTag("quantum.payment.open")) {
            Text("Pending payment / review", color = UIKit.colorScheme.text.accent)
        }
        Text(if (coinDetails) "TOS" else "History", style = UIKit.typography.h2, color = UIKit.colorScheme.text.primary)
        if (coinDetails) Text(balance ?: if (loading) "Verifying balance…" else "Balance unavailable", color = UIKit.colorScheme.text.primary)
        Text(status, color = UIKit.colorScheme.text.secondary,
            modifier = Modifier.testTag("quantum.history.status").semantics { contentDescription = status })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !loading, onClick = { request = PageRequest(0, null, true, request.serial + 1) },
                modifier = Modifier.testTag("quantum.history.newest")) { Text("Refresh", color = UIKit.colorScheme.text.accent) }
            TextButton(enabled = !loading && pageIndex > 0, onClick = {
                request = PageRequest(pageIndex - 1, starts[pageIndex - 1], false, request.serial + 1)
            }) { Text("Newer", color = UIKit.colorScheme.text.accent) }
            TextButton(enabled = !loading && next != null, onClick = {
                request = PageRequest(pageIndex + 1, next, false, request.serial + 1)
            }) { Text("Older", color = UIKit.colorScheme.text.accent) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("quantum.history.records")) {
            items(records, key = { it.hash }) { record ->
                Column(Modifier.fillMaxWidth().clickable { detail = record }.padding(vertical = 14.dp)
                    .testTag("quantum.history.record.${record.hash}")) {
                    Text(title(record), color = UIKit.colorScheme.text.primary)
                    val message = when (record.direction) {
                        QuantumHistoryTransaction.Direction.Incoming -> record.inbound
                        QuantumHistoryTransaction.Direction.Outgoing -> record.outbound.filter { it.kind == "internal" && !it.bounced }.singleOrNull()
                        else -> null
                    }
                    message?.amountNano?.let { value -> QuantumHistoryTransaction.formatNano(value)?.let {
                        Text(it + if (message.otherCurrencies) " + other assets" else "", color = UIKit.colorScheme.text.primary)
                    } }
                    Text(outcome(record), color = UIKit.colorScheme.text.secondary)
                    Text(date.format(Date(record.timestamp * 1000)), color = UIKit.colorScheme.text.secondary)
                }
            }
        }
    }
    detail?.let { record ->
        Dialog(onDismissRequest = { detail = null }) {
            Column(Modifier.fillMaxWidth().background(UIKit.colorScheme.background.page).verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("quantum.history.detail").semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Transaction", style = UIKit.typography.h2, color = UIKit.colorScheme.text.primary)
                val rows = listOf("Status" to outcome(record), "Transaction hash" to record.hash,
                    "Logical time" to record.logicalTime, "Account" to record.account,
                    "Timestamp" to date.format(Date(record.timestamp * 1000)),
                    "Transaction fee" to ((QuantumHistoryTransaction.formatNano(record.feesNano) ?: "Unavailable") +
                        if (record.otherFeeCurrencies) " + other assets" else "")) +
                    if (record.skippedActions > 0) listOf("Skipped actions" to record.skippedActions.toString()) else emptyList()
                for ((label, value) in rows) {
                    Text(label, color = UIKit.colorScheme.text.secondary); Text(value, color = UIKit.colorScheme.text.primary)
                }
                for ((index, message) in (listOfNotNull(record.inbound) + record.outbound).withIndex()) {
                    val label = if (index == 0 && record.inbound != null) "Incoming" else "Outgoing"
                    message.source?.let { Text("$label from\n$it", color = UIKit.colorScheme.text.primary) }
                    message.destination?.let { Text("$label to\n$it", color = UIKit.colorScheme.text.primary) }
                    message.amountNano?.let { value -> QuantumHistoryTransaction.formatNano(value)?.let {
                        Text("$label amount\n$it" + if (message.otherCurrencies) " + other assets" else "", color = UIKit.colorScheme.text.primary)
                    } }
                }
                TextButton(onClick = { detail = null }, modifier = Modifier.testTag("quantum.history.detail.done")) {
                    Text("Done", color = UIKit.colorScheme.text.accent)
                }
            }
        }
    }
}

private fun title(record: QuantumHistoryTransaction) = when (record.direction) {
    QuantumHistoryTransaction.Direction.Incoming -> if (record.inbound?.bounced == true) "Returned transfer" else "Incoming transfer"
    QuantumHistoryTransaction.Direction.Outgoing -> "Outgoing transfer"
    QuantumHistoryTransaction.Direction.Operation -> "Wallet operation"
}
private fun outcome(record: QuantumHistoryTransaction) = when (record.outcome) {
    QuantumHistoryTransaction.Outcome.Included -> "Included"
    QuantumHistoryTransaction.Outcome.Executed -> "Executed"
    QuantumHistoryTransaction.Outcome.SendSkipped -> "Send skipped"
    QuantumHistoryTransaction.Outcome.ExecutionFailed -> "Execution did not succeed"
}
