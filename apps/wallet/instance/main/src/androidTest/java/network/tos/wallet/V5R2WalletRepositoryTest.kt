/* Public frozen master/material only; never use for funds. */
package network.tos.wallet
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import network.tos.wallet.data.account.pq.V5R2WalletRepository
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.cell.buildCell
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class V5R2WalletRepositoryTest {
 private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 @Test fun authenticatedInitialRegistrationAndDerivedCustody() = runBlocking {
  val app = InstrumentationRegistry.getInstrumentation().targetContext
  val namespace = "v5r2-qa-${UUID.randomUUID()}"
  val prefs = app.getSharedPreferences(namespace, 0)
  fun tag(n: Int) = buildCell { storeUInt(n, 8) }
  val codes = V5R2Codes(tag(1), tag(2), tag(3))
  val pins = V5R2CodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
  val network = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
  val primary = V5R2Crypto.publicKey(V5R2Role.PRIMARY, hex("7e818ac507abf0c92d98bb1fe1cde59af6b050fe3c7a44544084c4852b82d455"))
  val rescue = V5R2Crypto.publicKey(V5R2Role.RESCUE, hex("6a722ced6bb6a327db70e5c4ee35f2cb5fcb53dd60fbf56fc724595c6564a6ca7d9e63457ac0bad4adb0ee44977e442c"))
  val profile = TosV5R2InitialRecovery.SeedProfile.RAW_MASTER_32
  val manifest = TosV5R2InitialRecovery.prepare(codes, pins, -239, network, 42, primary, rescue,
      V5R2Policy.REQUIRED, ByteArray(32) { 7 }, hex("000000010000000800000003" + "33".repeat(16) + "44".repeat(32)), 100,
      TosV5R2InitialRecovery.Derivation(0L, 0L, profile, profile, profile))
  val address = manifest.genesis.address
  var proofDirectory: java.io.File? = null
  var unlocked = false
  fun repository() = V5R2WalletRepository(app, codes, pins, { unlocked }, namespace)
  try {
   val repository = repository()
   assertTrue(runCatching { repository.registerInitial("cancelled", manifest.toJson(), address) }.isFailure)
   assertTrue(repository.list().isEmpty())
   unlocked = true
   val record = repository.registerInitial("QA strict R2", manifest.toJson(), address)
   proofDirectory = java.io.File(app.noBackupFilesDir, "v5r2-proof-checkpoints/" + record.id)
   assertEquals(address, repository().list().single().address)
   assertTrue(runCatching { repository.registerInitial("duplicate", manifest.toJson(), address) }.isFailure)
   val anchor = "{\"kind\":\"zerostate\",\"workchain\":-1,\"shard\":\"8000000000000000\",\"seqno\":0,\"root_hash\":\"1BDB1208416A1103BDB7FF6082F7EFFE047BAC45313E27D4043CA04D16377B64\",\"file_hash\":\"C9F382C9119EB7F3AB3BD6AE88FA1AF59AB500BDA3B5D794480E83EE24477686\"}".toByteArray()
   var proofCalls = 0
   val transport = V5R2ProofTransport { _, _ -> proofCalls++; error("Unexpected proof query") }
   unlocked = false
   val deniedObservation = runCatching {
    repository.observeInitial(record.id, address, anchor, false, false, transport)
   }.exceptionOrNull()
   assertEquals("Proof authentication gate bypassed", "R2 operation cancelled", deniedObservation?.message)
   assertEquals(0, proofCalls)
   unlocked = true
   val missing = runCatching { repository.observeInitial(record.id, address, anchor, false, false, transport) }.exceptionOrNull()
   assertTrue("Missing checkpoint did not refuse natively", missing is SecurityException)
   assertEquals("Missing checkpoint queried endpoint", 0, proofCalls)
   java.io.File(app.noBackupFilesDir, "v5r2-proof-checkpoints/" + record.id).deleteRecursively()

   unlocked = false
   val cancelled = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   assertTrue(runCatching { repository.restoreInitialRole(record.id, address, V5R2Role.PRIMARY, cancelled, V5R2SeedVault.MasterProfile.RAW_MASTER_32) }.isFailure)
   assertArrayEquals(ByteArray(32), cancelled)
   unlocked = true
   for ((role, expected) in listOf(V5R2Role.PRIMARY to primary, V5R2Role.RESCUE to rescue)) {
    val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    assertArrayEquals(expected, repository.restoreInitialRole(record.id, address, role, master, V5R2SeedVault.MasterProfile.RAW_MASTER_32))
    assertArrayEquals(ByteArray(32), master)
   }
   assertEquals(1, repository().list().size)
  } finally { proofDirectory?.deleteRecursively(); check(prefs.edit().clear().commit()) }
 }
}
