package network.tos.wallet.data.account

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.hex
import network.tos.extensions.toByteArray
import network.tos.extensions.writeBooleanCompat
import network.tos.extensions.writeEnum
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.account.source.DatabaseSource
import network.tos.wallet.data.rn.data.RNWallet
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.api.pub.PublicKeyEd25519
import java.io.File
import java.util.UUID

/** Exercises Android SQLite/Parcel, including the real database upgrade and RN migration mapper. */
@RunWith(AndroidJUnit4::class)
class QuantumWalletIdentityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "quantum-model-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getDatabasePath(name: String) = File(directory, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private val fixture = JSONObject(instrumentation.context.assets.open("tos-quantum-self-pay.json").bufferedReader().use { it.readText() })
    private val label = Wallet.Label("Quantum", "custom_wallet", 0xff293342.toInt())
    private fun quantum(): WalletEntity = WalletEntity("quantum-test",
        WalletIdentity.Quantum(WalletIdentity.quantumFromBase64(fixture.getString("identity_boc"))),
        Wallet.Type.Default, label, initialized = true)
    private fun classical(version: WalletVersion = WalletVersion.TOSV5R1) = WalletEntity(
        "classic-${version.id}", PublicKeyEd25519(ByteArray(32) { it.toByte() }), Wallet.Type.Default,
        version, label.copy(accountName = version.title), initialized = true, networkGlobalId = 42)

    @After fun cleanTestDatabase() { directory.deleteRecursively() }

    @Test fun sqliteReopenPreservesCompleteBirthIdentityAndAddress() = runBlocking {
        val wallet = quantum()
        DatabaseSource(context).use { it.insertAccounts(listOf(wallet, classical())) }
        DatabaseSource(context).use { database ->
            val restored = requireNotNull(database.getAccount(wallet.id))
            assertEquals(wallet, restored)
            assertEquals(fixture.getString("wallet_address"), restored.accountId)
            assertEquals(wallet.address, restored.address)
            assertArrayEquals(wallet.quantumIdentity!!.toBoc(), restored.quantumIdentity!!.toBoc())
            assertNull(restored.classicalPublicKey)
            database.readableDatabase.rawQuery("SELECT public_key,quantum_identity,network_global_id FROM wallet WHERE id=?", arrayOf(wallet.id)).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertArrayEquals(wallet.quantumIdentity!!.toBoc(), it.getBlob(1))
                assertEquals(42, it.getInt(2))
            }
        }
    }

    @Test fun failedRegistrationThrowsAndRollsBackTheEntireBatch() = runBlocking {
        val wallet = quantum()
        DatabaseSource(context).use { db ->
            db.insertAccounts(listOf(wallet))
            val error = runCatching { db.insertAccounts(listOf(classical(), wallet)) }.exceptionOrNull()
            assertTrue(error is android.database.sqlite.SQLiteConstraintException)
            assertEquals(listOf(wallet), db.getAccounts())
        }
        DatabaseSource(context).use { assertEquals(listOf(wallet), it.getAccounts()) }
    }

    @Test fun versionFiveUpgradePreservesAllSevenClassicalWallets() = runBlocking {
        val wallets = WalletVersion.entries.filter { it != WalletVersion.UNKNOWN && it != WalletVersion.QUANTUM_SELF_PAY }.map(::classical)
        context.openOrCreateDatabase("account", Context.MODE_PRIVATE, null).use { db ->
            // The actual v5 schema, before quantum_identity existed.
            db.execSQL("CREATE TABLE wallet (id TEXT PRIMARY KEY, public_key BLOB, type INTEGER, version TEXT, label BLOB, ledger_device_id TEXT, ledger_account_index INTEGER, keystone_xfp TEXT, keystone_path TEXT, initialized INTEGER DEFAULT 0, network_global_id INTEGER)")
            for (wallet in wallets) db.insertOrThrow("wallet", null, ContentValues().apply {
                put("id", wallet.id); put("public_key", wallet.publicKey.key.toByteArray())
                put("type", wallet.type.id); put("version", wallet.version.id)
                put("label", wallet.label.toByteArray()); put("initialized", 1); put("network_global_id", 42)
            })
            db.version = 5
        }
        DatabaseSource(context).use { db ->
            assertEquals(wallets.toSet(), db.getAccounts().toSet())
            assertEquals(6, db.readableDatabase.version)
            for (wallet in wallets) assertEquals(wallet.address, db.getAccount(wallet.id)!!.address)
            db.insertAccounts(listOf(quantum()))
        }
        DatabaseSource(context).use { assertEquals(8, it.getAccounts().size) }
    }

    @Test fun sqliteRejectsMissingMixedOrWrongNetworkIdentity() = runBlocking {
        val mutations = listOf(
            "quantum_identity=NULL", "public_key=zeroblob(32)", "network_global_id=43",
            "network_global_id=NULL", "version=6", "type=5", "ledger_device_id='device'", "keystone_path='path'"
        )
        for (mutation in mutations) {
            DatabaseSource(context).use { database ->
                database.clearAccounts()
                database.insertAccounts(listOf(quantum()))
                database.writableDatabase.execSQL("UPDATE wallet SET $mutation")
                val failure = runCatching { database.getAccounts() }.exceptionOrNull()
                assertTrue("Accepted malformed row: $mutation ($failure)", failure is IllegalArgumentException)
            }
        }
    }

    @Test fun parcelPreservesQuantumIdentityAndDoesNotConsumeFollowingFields() {
        for (wallet in listOf(quantum(), classical())) {
            val parcel = Parcel.obtain()
            try {
                wallet.writeToParcel(parcel, 0)
                parcel.writeInt(0x12345678)
                parcel.setDataPosition(0)
                val restored = WalletEntity.CREATOR.createFromParcel(parcel)
                assertEquals(wallet, restored)
                assertEquals(wallet.address, restored.address)
                assertEquals(0x12345678, parcel.readInt())
                assertEquals(0, parcel.dataAvail())
            } finally { parcel.recycle() }
        }
    }

    @Test fun oldClassicalParcelStillDecodesAndMixedQuantumParcelFails() {
        fun decode(key: String, version: WalletVersion, network: Int?): WalletEntity {
            val parcel = Parcel.obtain()
            try {
                parcel.writeString("old-format"); parcel.writeString(key)
                parcel.writeEnum(Wallet.Type.Default); parcel.writeEnum(version)
                parcel.writeParcelable(label, 0); parcel.writeParcelable(null, 0); parcel.writeParcelable(null, 0)
                parcel.writeBooleanCompat(true)
                parcel.writeByte(if (network == null) 0.toByte() else 1.toByte())
                network?.let(parcel::writeInt)
                parcel.setDataPosition(0)
                return WalletEntity.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        val classic = classical()
        assertEquals(classic.accountId, decode(classic.publicKey.hex(), classic.version, 42).accountId)
        val encoded = "qsi1:" + (quantum().identity as WalletIdentity.Quantum).toBase64()
        assertEquals(quantum().accountId, decode(encoded, WalletVersion.QUANTUM_SELF_PAY, 42).accountId)
        for (network in listOf(null, 43)) assertThrows(IllegalArgumentException::class.java) { decode(encoded, WalletVersion.QUANTUM_SELF_PAY, network) }
        assertThrows(IllegalArgumentException::class.java) { decode(encoded, WalletVersion.TOSV5R1, 42) }
        assertThrows(IllegalArgumentException::class.java) { decode(classic.publicKey.hex(), WalletVersion.QUANTUM_SELF_PAY, 42) }
    }

    @Test fun rnMirrorRestoresQuantumAndRetainsClassicalNetworkBinding() {
        for (wallet in listOf(quantum(), quantum().copy(type = Wallet.Type.Testnet), classical())) {
            val json = wallet.toRNWallet().toJSON()
            val restored = requireNotNull(RNWallet(JSONObject(json.toString())).toWalletEntity())
            assertEquals(wallet.copy(initialized = false), restored)
            assertEquals(wallet.accountId, restored.accountId)
            assertEquals(wallet.address, restored.address)
            assertEquals(wallet.networkGlobalId, restored.networkGlobalId)
        }
        assertFalse(quantum().toRNWallet().toJSON().has("pubkey"))
    }

    @Test fun rnMirrorRejectsIdentityConfusionAndNoncanonicalEncoding() {
        val badFields = listOf(
            "pubkey" to classical().publicKey.hex(), "version" to "tosV5R1", "type" to "Regular",
            "networkGlobalId" to 43, "networkGlobalId" to JSONObject.NULL, "networkGlobalId" to "42",
            "networkGlobalId" to 42.5, "networkGlobalId" to 4294967338L,
            "quantumIdentity" to JSONObject.NULL, "quantumIdentity" to "",
            "quantumIdentity" to (fixture.getString("identity_boc") + "\n"),
            "workchain" to -1, "workchain" to 0.5, "workchain" to "0", "network" to 999,
            "network" to -239.5, "network" to "-239", "path" to "m/44", "xfp" to "key",
            "ledger" to "device", "keystone" to "key",
            "ledger" to JSONObject().put("deviceId", "device").put("deviceModel", "nano").put("accountIndex", 0),
            "keystone" to JSONObject().put("xfp", "key").put("path", "m/44")
        )
        for ((key, value) in badFields) {
            val json = quantum().toRNWallet().toJSON().put(key, value)
            assertThrows("Accepted $key=$value", IllegalArgumentException::class.java) { RNWallet(json).toWalletEntity() }
        }
    }

    @Test fun quantumCannotEnterClassicalSigningOrPhraseOnlyBackup() {
        val wallet = quantum()
        assertFalse(wallet.hasPrivateKey)
        assertFalse(wallet.isSendSupported)
        assertFalse(wallet.isTonConnectSupported)
        assertThrows(IllegalArgumentException::class.java) { wallet.publicKey }
        assertThrows(IllegalArgumentException::class.java) { wallet.contract }
        assertThrows(IllegalArgumentException::class.java) { wallet.copy(type = Wallet.Type.Ledger) }
        assertThrows(IllegalArgumentException::class.java) { wallet.copy(keystone = WalletEntity.Keystone("key", "path")) }
        assertTrue(classical().hasPrivateKey)
        assertTrue(classical().isSendSupported)
    }
}
