/* Public frozen master and material only; never use for funds. */
package network.tos.security.pq
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.KeyGenerator
@RunWith(AndroidJUnit4::class)
class QuantumDerivedRestoreDeviceTest {
 private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 @Test fun recoveredRolesBindExpectedPublicKeysAndWipeInputs() {
  val app = ApplicationProvider.getApplicationContext<android.content.Context>()
  val prefs = app.getSharedPreferences("quantum-derived-${UUID.randomUUID()}", 0)
  val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  val context = QuantumSeedVault.Context(hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 0L, 0L)
  val vault = QuantumSeedVault(prefs)
  try {
   run {
    val role = QuantumRole.PRIMARY
    val expected = QuantumCrypto.publicKey(role, hex("7e818ac507abf0c92d98bb1fe1cde59af6b050fe3c7a44544084c4852b82d455"))
    val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    assertArrayEquals(expected, vault.restoreDerivedAndWipe("test", role, context, wrapping, master,
        QuantumSeedVault.MasterProfile.RAW_MASTER_32, QuantumSeedVault.MasterProfile.RAW_MASTER_32, expected))
    assertArrayEquals(ByteArray(32), master)
    assertArrayEquals(expected, QuantumSeedVault(prefs).publicKey("test", role, context, wrapping))
    val bad = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val wrongKey = expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val error = runCatching { vault.restoreDerivedAndWipe("wrong", role, context, wrapping, bad,
        QuantumSeedVault.MasterProfile.RAW_MASTER_32, QuantumSeedVault.MasterProfile.RAW_MASTER_32, wrongKey) }.exceptionOrNull()
    assertEquals("Recovered key differs from enrollment", error?.message)
    assertArrayEquals(ByteArray(32), bad)
   }
   run {
    val role = QuantumRole.RESCUE
    val expected = QuantumCrypto.publicKey(role, hex("6a722ced6bb6a327db70e5c4ee35f2cb5fcb53dd60fbf56fc724595c6564a6ca7d9e63457ac0bad4adb0ee44977e442c"))
    val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    assertArrayEquals(expected, vault.restoreDerivedAndWipe("test", role, context, wrapping, master,
        QuantumSeedVault.MasterProfile.RAW_MASTER_32, QuantumSeedVault.MasterProfile.RAW_MASTER_32, expected))
    assertArrayEquals(ByteArray(32), master)
    assertArrayEquals(expected, QuantumSeedVault(prefs).publicKey("test", role, context, wrapping))
    val bad = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val wrongKey = expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val error = runCatching { vault.restoreDerivedAndWipe("wrong", role, context, wrapping, bad,
        QuantumSeedVault.MasterProfile.RAW_MASTER_32, QuantumSeedVault.MasterProfile.RAW_MASTER_32, wrongKey) }.exceptionOrNull()
    assertEquals("Recovered key differs from enrollment", error?.message)
    assertArrayEquals(ByteArray(32), bad)
   }
   val mismatch = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val profileKey = QuantumCrypto.publicKey(QuantumRole.PRIMARY, hex("7e818ac507abf0c92d98bb1fe1cde59af6b050fe3c7a44544084c4852b82d455"))
   assertTrue(runCatching { vault.restoreDerivedAndWipe("profile", QuantumRole.PRIMARY, context, wrapping, mismatch,
       QuantumSeedVault.MasterProfile.RAW_MASTER_32, QuantumSeedVault.MasterProfile.NATIVE_MNEMONIC, profileKey) }.isFailure)
   assertArrayEquals(ByteArray(32), mismatch)
  } finally { check(prefs.edit().clear().commit()) }
 }
}
