package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.wallet.api.tos.TosPqReceipt
import org.json.JSONObject

/** Synchronously persisted public metadata. Save before broadcast, including when
 * an acknowledgement is lost; unknown operations are never resent automatically. */
class PqOperationJournal(context: Context) {
    private val preferences = context.getSharedPreferences("tos-pq-operations-v1", Context.MODE_PRIVATE)
    data class Entry(val intent: TosPqReceipt.Intent, val seqno: Int, val expires: Long, val status: TosPqReceipt.Status) {
        val terminal: Boolean get() = status in setOf(TosPqReceipt.Status.DEPLOYED, TosPqReceipt.Status.DELIVERED, TosPqReceipt.Status.FAILED, TosPqReceipt.Status.EXPIRED)
    }
    @Synchronized fun read(walletAddress: String): Entry? {
        val value = preferences.getString(walletAddress, null) ?: return null
        val j = JSONObject(value)
        return Entry(TosPqReceipt.Intent(j.getString("payer"), j.getString("hash"), j.getString("module"), walletAddress,
            j.optString("recipient").takeIf { it.isNotEmpty() }, j.getLong("amount")), j.getInt("seqno"), j.getLong("expires"), TosPqReceipt.Status.valueOf(j.getString("status")))
    }
    @Synchronized fun write(entry: Entry) {
        val i = entry.intent
        val j = JSONObject().put("payer", i.feeAddress).put("hash", i.externalHash).put("module", i.moduleAddress)
            .put("recipient", i.recipient ?: "").put("amount", i.amount).put("seqno", entry.seqno).put("expires", entry.expires).put("status", entry.status.name)
        check(preferences.edit().putString(i.walletAddress, j.toString()).commit()) { "Could not persist submission" }
    }
}
