package network.tos.wallet.data.account

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.rn.RNLegacy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumMirrorPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "quantum-mirror-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getDatabasePath(name: String) = File(directory, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun mirror() = RNLegacy(context, scope)
    private fun database() = SQLiteDatabase.openDatabase(context.getDatabasePath("RKStorage").path, null, SQLiteDatabase.OPEN_READWRITE)
    private fun wallet(id: String = "enrollment") = WalletEntity(id,
        WalletIdentity.Quantum(WalletIdentity.quantumFromBase64(JSONObject(instrumentation.context.assets.open("tos-quantum-self-pay.json").bufferedReader().use { it.readText() }).getString("identity_boc"))),
        Wallet.Type.Default, Wallet.Label("Quantum", "custom_wallet", 0xff293342.toInt()), initialized = false).toRNWallet()

    @After fun clean() { scope.cancel(); directory.deleteRecursively() }

    @Test fun actualSqlWriteFailureDoesNotPublishCacheAndRetryIsIdempotent() = runBlocking {
        val store = mirror()
        val initial = wallet()
        store.addWallet(initial)
        database().use { it.execSQL("CREATE TRIGGER refuse_wallet_write BEFORE INSERT ON catalystLocalStorage WHEN NEW.key='walletsStore' BEGIN SELECT RAISE(ABORT, 'injected mirror failure'); END") }
        val next = wallet("second")
        val failure = runCatching { store.addWallet(next) }.exceptionOrNull()
        assertTrue(failure is android.database.sqlite.SQLiteException)
        assertTrue(failure!!.message!!.contains("injected mirror failure"))
        assertEquals(listOf(initial), store.getWallets().wallets)
        assertEquals(listOf(initial), mirror().getWallets().wallets)
        database().use { it.execSQL("DROP TRIGGER refuse_wallet_write") }
        store.addWallet(next); store.addWallet(next)
        assertEquals(listOf(initial, next), mirror().getWallets().wallets)
        assertEquals("Wallet mirror identifier collision", runCatching { store.addWallet(next.copy(networkGlobalId = 999)) }.exceptionOrNull()?.message)
        assertEquals(listOf(initial, next), mirror().getWallets().wallets)
    }

    @Test fun corruptOrUnreadableMirrorIsNeverReplacedAsEmpty() = runBlocking {
        val store = mirror()
        store.addWallet(wallet())
        for (value in listOf("broken", "{}", "{\"wallets\":[]} trailing")) {
            database().use { it.execSQL("UPDATE catalystLocalStorage SET value=? WHERE key='walletsStore'", arrayOf(value)) }
            assertNotNull(runCatching { store.getWallets() }.exceptionOrNull())
            assertNotNull(runCatching { store.addWallet(wallet("new")) }.exceptionOrNull())
            database().use { db -> db.rawQuery("SELECT value FROM catalystLocalStorage WHERE key='walletsStore'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(value, it.getString(0))
            } }
        }
        database().use { it.execSQL("ALTER TABLE catalystLocalStorage RENAME TO unavailable") }
        assertNotNull(runCatching { store.getWallets() }.exceptionOrNull())
        assertNotNull(runCatching { store.addWallet(wallet()) }.exceptionOrNull())
    }

    @Test fun simultaneousInstancesAndPreferenceUpdatesPreserveAllWallets() = runBlocking {
        val first = mirror(); val second = mirror()
        coroutineScope {
            (0 until 24).map { index -> launch(Dispatchers.IO) {
                val store = if (index % 2 == 0) first else second
                store.addWallet(wallet("wallet-$index"))
                store.updateWalletPreferences(biometry = true)
            } }.joinAll()
        }
        val reloaded = mirror().getWallets()
        assertEquals((0 until 24).map { "wallet-$it" }.toSet(), reloaded.wallets.map { it.identifier }.toSet())
        assertTrue(reloaded.biometryEnabled)
        first.setSelectedWallet("wallet-2")
        second.delete("wallet-2")
        assertEquals("", first.getWallets().selectedIdentifier)
        first.clear()
        assertTrue(second.getWallets().wallets.isEmpty())
    }
}
