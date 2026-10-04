package network.tos.wallet

import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.api.tos.TosPqReceipt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PqReceiptTest {
    @Test fun journalSurvivesReloadAndRejectsCorruptMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tos-pq-operations-v1", android.content.Context.MODE_PRIVATE)
        val address = "0:"+"42".repeat(32)
        val previous = preferences.getString(address,null)
        try {
            val intent = TosPqReceipt.Intent("0:"+"43".repeat(32), "PUBLIC external hash", "0:"+"44".repeat(32), address)
            val journal = network.tos.wallet.data.account.pq.PqOperationJournal(context)
            journal.write(network.tos.wallet.data.account.pq.PqOperationJournal.Entry(intent,9,2000000600,TosPqReceipt.Status.PENDING))
            val loaded = network.tos.wallet.data.account.pq.PqOperationJournal(context).read(address)!!
            assertEquals(intent.externalHash,loaded.intent.externalHash);assertFalse(loaded.terminal)
            preferences.edit().putString(address,"corrupt").commit()
            assertThrows(org.json.JSONException::class.java) { journal.read(address) }
        } finally {
            preferences.edit().apply { if(previous==null)remove(address) else putString(address,previous) }.commit()
        }
    }

    @Test fun receiptsBindEveryHopAndDoNotInferDeliveryFromNonceOrFeePayment() {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("tos-pq-receipt-vectors.json").bufferedReader().use { it.readText() }
        val fixture = JSONObject(raw);val i = fixture.getJSONObject("intent")
        val intent = TosPqReceipt.Intent(i.getString("feeAddress"),i.getString("externalHash"),i.getString("moduleAddress"),i.getString("walletAddress"),i.getString("recipient"),i.getLong("amount"))
        fun data() = mutableMapOf(intent.feeAddress to mutableListOf(fixture.getJSONObject("fee")),
            intent.moduleAddress to mutableListOf(fixture.getJSONObject("module")),intent.walletAddress to mutableListOf(fixture.getJSONObject("wallet")),
            intent.recipient!! to mutableListOf(fixture.getJSONObject("recipient")))
        fun run(map: Map<String,List<JSONObject>>) = TosPqReceipt.reconcile(intent, { map[it] ?: emptyList() }, { false })
        assertEquals(TosPqReceipt.Status.DELIVERED,run(data()))
        assertEquals(TosPqReceipt.Status.PENDING,run(emptyMap()))
        assertEquals(TosPqReceipt.Status.AUTH_ACCEPTED,run(data().apply { remove(intent.recipient) }))
        assertEquals(TosPqReceipt.Status.FEE_ACCEPTED,run(data().apply { remove(intent.moduleAddress) }))
        val fractional = data();val module = JSONObject(fixture.getJSONObject("module").toString());module.getJSONObject("action").put("messages_created",1.1);fractional[intent.moduleAddress]=mutableListOf(module)
        assertEquals(TosPqReceipt.Status.FEE_ACCEPTED,run(fractional))
        val wrong = data();wrong[intent.recipient!!] = mutableListOf(JSONObject(fixture.getJSONObject("recipient").toString()).put("in_msg_hash", "UNRELATED"))
        assertEquals(TosPqReceipt.Status.AUTH_ACCEPTED,run(wrong))
        val failed = data();failed[intent.walletAddress] = mutableListOf(JSONObject(fixture.getJSONObject("wallet").toString()).put("aborted",true).put("out_msgs",org.json.JSONArray()))
        assertEquals(TosPqReceipt.Status.FAILED,run(failed))
        val forged = data();val wallet = JSONObject(fixture.getJSONObject("wallet").toString());wallet.getJSONArray("out_msgs").getJSONObject(0).put("value","10000001");forged[intent.walletAddress]=mutableListOf(wallet)
        assertThrows(IllegalArgumentException::class.java) { run(forged) }
        val bounced = data();val recipient=JSONObject(fixture.getJSONObject("recipient").toString());recipient.getJSONObject("in_msg").put("bounced",true);bounced[intent.recipient!!]=mutableListOf(recipient)
        assertThrows(IllegalArgumentException::class.java) { run(bounced) }
    }
}
