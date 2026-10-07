/* Public test seed and path only; never use for funds. */
package network.tos.security.pq
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
@RunWith(AndroidJUnit4::class)
class V5R2FeeSeedVaultDeviceTest {
 private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 @Test fun encryptedFeeSeedBindsEnrollmentAndSession() {
  val seed = hex("7015e4db70b1a82cc657f3e45559bc99de99a7dc3d0c2d5b259bbc8d2300275d86eab5e984642406788230273d09951c")
  val key = hex("00000001000000080000000386eab5e984642406788230273d09951c0fa04c5e002df6bb037be303270f754a8d35e0bdee7c108ac525402431dc9005")
  val path4 = hex("b2dcbd54388cdba348372ccba7a5685933722635903e78a31bb21acb178cbd01f3893a80184b89a151c25785abc76424aa6ad353230c2a8d048497e2388990c45a88fb635db4d36f27064a123fea7cb850a6270aa12582d1fbd0eb723f78e13b2dfc1c5f559809177191feb8a03c337d5bd102b0cb626594e62eb6948e2635d9413c162436fe107cc0a02e1cc9236f5483aff37b07650a47edfbf4386c74422d22edf2214600ddb73463e5799f527f66914c6ad9110fdc064d1bc1ef69d3f6a03029d7608d340f27f50a23827153e0449a2fb1989be0b5fe5181cf6ad9536af31c5ca2028de4f77f6dd25dd1cf8e7193724890daa275e0407b830e79141ea4a4e4a371a3c779d4aac2c62c8374408b59763ec004b9896e7bc9ac18149f2f0907d4f5f5bfca024816d76e3470328824d2ceb5326bfbd97ba2d77c06b90be89b75c164f997cc610ff922e66923f23687146260bc9643524d56f094452d6de7bb47ae6e05e513efd1ec2b13900c34b0b3d93bdb9c39cb936cd0dabce267423cdc477b60a0d5a2678f01d67008d7fbd02086ea22467330a90341b987e9796c321be55442140f2e935b8b9e95dd945bef260bd6daa4693323ec32fa6c6094f01658aea398306e3f8b4b5f10df18a459fd6104958d2c12ec5eac583f9565fedacd4f9d5930c06b666aa3efd40bf0db050ce9b7225a3395f9d82080e13c06dc2424dd8b01ad96213985b90ce95708daf5be545f5f3ea97af32fba32d6ab4c9191feb36ca4e7f896af08e2a956352ce872031e8f4e2a0f0be93151ff6b09a96e259863ce0f80c42e840af3d1d90c80d25ed4bbb6ffdc5c0f8717f6eb8f8ac407d3ca6358b9ff4bfef00413a35b67cd467c0debfe952351061d4d288381c819bda252340b")
  val app = ApplicationProvider.getApplicationContext<android.content.Context>()
  val prefs = app.getSharedPreferences("v5r2-fee-custody-${UUID.randomUUID()}", 0)
  val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  val n = ByteArray(32) { 1 }; val v = ByteArray(32) { 2 }; val t = ByteArray(32) { 3 }
  val enrollment = V5R2FeeSeedVault.Enrollment(42, n, v, t, 100, key, 0, 0)
  val store = V5R2FeeSeedVault(prefs)
  fun directory() = File(app.cacheDir, "v5r2-fee-vault-${UUID.randomUUID()}").also { check(it.mkdir()); Os.chmod(it.path, 448) }
  val dir = directory(); val wrongDir = directory()
  try {
   val invalid = seed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
   val error = runCatching { store.importAndWipe("bad", enrollment, wrapping, invalid, 4, path4) }.exceptionOrNull()
   assertTrue(error is IllegalArgumentException)
   assertEquals("Fee seed does not bind enrollment", error?.message)
   assertArrayEquals(ByteArray(48), invalid)
   val input = seed.copyOf()
   store.importAndWipe("test", enrollment, wrapping, input, 4, path4)
   assertArrayEquals(ByteArray(48), input)
   V5R2FeeState.open(wrongDir, 42, n, ByteArray(32) { 4 }, t, 100, 100).use { session ->
    val refusal = runCatching { store.sign("test", enrollment, wrapping, session, 3700, 0, 4, ByteArray(32) { 7 }, path4) }.exceptionOrNull()
    assertTrue(refusal is IllegalArgumentException)
    assertEquals("Fee custody route does not match signing session", refusal?.message)
    assertEquals(4L, session.preview(3700, 0))
   }
   V5R2FeeState.open(dir, 42, n, v, t, 100, 100).use { session ->
    val digest = ByteArray(32) { 7 }
    val changed = V5R2FeeSeedVault.Enrollment(42, n, v, t, 100, key, 0, 1)
    val contextError = runCatching { store.sign("test", changed, wrapping, session, 3700, 0, 4, digest, path4) }.exceptionOrNull()
    assertTrue(contextError is javax.crypto.AEADBadTagException)
    assertEquals(4L, session.preview(3700, 0))
    val signature = V5R2FeeSeedVault(prefs).sign("test", enrollment, wrapping, session, 3700, 0, 4, digest, path4)
    assertArrayEquals(signature, session.cachedVerified(4, digest, key))
    assertEquals(5L, session.preview(3700, 0))
   }
  } finally { seed.fill(0); check(prefs.edit().clear().commit()); dir.deleteRecursively(); wrongDir.deleteRecursively() }
 }
}
