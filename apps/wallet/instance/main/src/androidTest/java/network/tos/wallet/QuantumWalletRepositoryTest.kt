/* Public frozen master/material only; never use for funds. */
package network.tos.wallet
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import network.tos.wallet.data.account.pq.QuantumWalletRepository
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.cell.buildCell
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class QuantumWalletRepositoryTest {
 private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 @Test fun authenticatedInitialRegistrationAndDerivedCustody() = runBlocking {
  val app = InstrumentationRegistry.getInstrumentation().targetContext
  val namespace = "quantum-qa-${UUID.randomUUID()}"
  val prefs = app.getSharedPreferences(namespace, 0)
  fun tag(n: Int) = buildCell { storeUInt(n, 8) }
  val codes = QuantumCodes(tag(1), tag(2), tag(3))
  val pins = QuantumCodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
  val network = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
  val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, hex("7e818ac507abf0c92d98bb1fe1cde59af6b050fe3c7a44544084c4852b82d455"))
  val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, hex("6a722ced6bb6a327db70e5c4ee35f2cb5fcb53dd60fbf56fc724595c6564a6ca7d9e63457ac0bad4adb0ee44977e442c"))
  val profile = TosQuantumInitialRecovery.SeedProfile.RAW_MASTER_32
  val manifest = TosQuantumInitialRecovery.prepare(codes, pins, -239, network, 42, primary, rescue,
      QuantumPolicy.REQUIRED, ByteArray(32) { 7 }, hex("000000010000000800000003" + "33".repeat(16) + "44".repeat(32)), 100,
      TosQuantumInitialRecovery.Derivation(0L, 0L, profile, profile, profile))
  val address = manifest.genesis.address
  var proofDirectory: java.io.File? = null
  var unlocked = false
  fun repository() = QuantumWalletRepository(app, codes, pins, { unlocked }, namespace)
  try {
   val repository = repository()
   assertTrue(runCatching { repository.registerInitial("cancelled", manifest.toJson(), address) }.isFailure)
   assertTrue(repository.list().isEmpty())
   unlocked = true
   val record = repository.registerInitial("QA strict R2", manifest.toJson(), address)
   proofDirectory = java.io.File(app.noBackupFilesDir, "quantum-proof-checkpoints/" + record.id)
   assertEquals(address, repository().list().single().address)
   assertTrue(runCatching { repository.registerInitial("duplicate", manifest.toJson(), address) }.isFailure)
   val anchor = "{\"kind\":\"zerostate\",\"workchain\":-1,\"shard\":\"8000000000000000\",\"seqno\":0,\"root_hash\":\"1BDB1208416A1103BDB7FF6082F7EFFE047BAC45313E27D4043CA04D16377B64\",\"file_hash\":\"C9F382C9119EB7F3AB3BD6AE88FA1AF59AB500BDA3B5D794480E83EE24477686\"}".toByteArray()
   var proofCalls = 0
   val transport = QuantumProofTransport { _, _ -> proofCalls++; error("Unexpected proof query") }
   unlocked = false
   val deniedObservation = runCatching {
    repository.observeInitial(record.id, address, anchor, false, false, transport)
   }.exceptionOrNull()
   assertEquals("Proof authentication gate bypassed", "R2 operation cancelled", deniedObservation?.message)
   val deniedPreparation = runCatching {
    repository.preparePrimaryExecute(record.id, address, anchor, false, buildCell { }, System.currentTimeMillis() / 1000 + 60, transport)
   }.exceptionOrNull()
   assertEquals("AUTH preparation bypassed authentication", "R2 operation cancelled", deniedPreparation?.message)
   val deniedCustody = runCatching {
    repository.prepareInitialPrimaryWithCustody(record.id, address, anchor, false, buildCell { }, System.currentTimeMillis() / 1000 + 60, transport)
   }.exceptionOrNull()
   assertEquals("Custody preparation bypassed authentication", "R2 operation cancelled", deniedCustody?.message)
   assertEquals(0, proofCalls)
   unlocked = true
   val missing = runCatching { repository.observeInitial(record.id, address, anchor, false, false, transport) }.exceptionOrNull()
   assertTrue("Missing checkpoint did not refuse natively", missing is SecurityException)
   assertEquals("Missing checkpoint queried endpoint", 0, proofCalls)
   val missingPreparation = runCatching {
    repository.preparePrimaryExecute(record.id, address, anchor, false, buildCell { }, System.currentTimeMillis() / 1000 + 60, transport)
   }.exceptionOrNull()
   assertTrue("AUTH preparation bypassed checkpoint proof", missingPreparation is SecurityException)
   val missingCustody = runCatching {
    repository.prepareInitialPrimaryWithCustody(record.id, address, anchor, false, buildCell { }, System.currentTimeMillis() / 1000 + 60, transport)
   }.exceptionOrNull()
   assertTrue("Custody accessed before authenticated checkpoint", missingCustody is SecurityException)
   assertEquals(0, proofCalls)
   val incompatible = TosQuantumGenesis(codes, pins, -239, ByteArray(32), 42, primary, rescue,
       QuantumPolicy.REQUIRED, ByteArray(32) { 8 }, hex("000000010000000800000003" + "33".repeat(16) + "44".repeat(32)), 100)
   val routeError = runCatching {
    repository.observeSuccessor(record.id, address, anchor, incompatible, false, false, transport)
   }.exceptionOrNull()
   assertEquals("Incompatible successor reached proof acquisition", "Successor namespace mismatch", routeError?.message)
   assertEquals(0, proofCalls)
   val queryStarted = CountDownLatch(1)
   val pending = launch(Dispatchers.Default) {
    val outcome = runCatching {
     repository.observeInitial(record.id, address, anchor, true, false, QuantumProofTransport { _, _ ->
      queryStarted.countDown()
      Thread.sleep(20000)
      error("Uncancelled test transport")
     })
    }
    assertTrue("Cancelled observation returned", outcome.isFailure)
   }
   try {
    assertTrue("Proof query did not start", queryStarted.await(10, TimeUnit.SECONDS))
    val cancelStart = SystemClock.elapsedRealtime()
    pending.cancelAndJoin()
    assertTrue("Cancelled proof transport was not interrupted", SystemClock.elapsedRealtime() - cancelStart < 5000)
    assertTrue(pending.isCancelled)
    assertFalse("Cancelled proof committed checkpoint", java.io.File(proofDirectory, "checkpoint.json").exists())
   } finally { pending.cancelAndJoin() }
   val released = runCatching {
    repository.observeInitial(record.id, address, anchor, true, false, QuantumProofTransport { _, _ ->
     throw IllegalStateException("Public lock-release probe")
    })
   }.exceptionOrNull()
   assertEquals("Cancelled proof kept checkpoint lock", "Public lock-release probe", released?.message)

   java.io.File(app.noBackupFilesDir, "quantum-proof-checkpoints/" + record.id).deleteRecursively()

   unlocked = false
   val cancelled = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   assertTrue(runCatching { repository.restoreInitialRole(record.id, address, QuantumRole.PRIMARY, cancelled, QuantumSeedVault.MasterProfile.RAW_MASTER_32) }.isFailure)
   assertArrayEquals(ByteArray(32), cancelled)
   unlocked = true
   for ((role, expected) in listOf(QuantumRole.PRIMARY to primary, QuantumRole.RESCUE to rescue)) {
    val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    assertArrayEquals(expected, repository.restoreInitialRole(record.id, address, role, master, QuantumSeedVault.MasterProfile.RAW_MASTER_32))
    assertArrayEquals(ByteArray(32), master)
   }
   assertEquals(1, repository().list().size)
  } finally { proofDirectory?.deleteRecursively(); check(prefs.edit().clear().commit()) }
 }
}
