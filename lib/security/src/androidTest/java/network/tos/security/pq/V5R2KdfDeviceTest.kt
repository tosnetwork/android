package network.tos.security.pq
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class V5R2KdfDeviceTest {
 private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 @Test fun frozenDerivationsAndMasterWipe() {
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.PRIMARY, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 0L, 0L, null)
   assertArrayEquals(hex("7e818ac507abf0c92d98bb1fe1cde59af6b050fe3c7a44544084c4852b82d455"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.RESCUE, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 0L, 0L, null)
   assertArrayEquals(hex("6a722ced6bb6a327db70e5c4ee35f2cb5fcb53dd60fbf56fc724595c6564a6ca7d9e63457ac0bad4adb0ee44977e442c"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.PRIMARY, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 1L, 1L, null)
   assertArrayEquals(hex("a6e88bc3532b29ccb4cd2ee6b4bbca1fab3344051876f34f90e4e59ddf99a16d"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.RESCUE, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 1L, 1L, null)
   assertArrayEquals(hex("6645b7ee6340ed3efe5392a8388dfcbda20514497449471eff81901fbf23e01d6a2e02909c4e1cf6106367f1eac94691"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.FEE, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 0L, 0L, hex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5"))
   assertArrayEquals(hex("5169cfdc6dac3befe15b3204d98c5bc6b26458ff10d342890ea5cbfd4befcedf29816078a9bdffedf4036e0558c3c687"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.FEE, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 0L, 0L, hex("a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6"))
   assertArrayEquals(hex("ac999fa55ec443415e17f5e016fb6b6e4f0b0ea25b8ab89768e379f1ab09c60d70cecdb284835eb86839568c9de2f026"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
  run {
   val master = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
   val actual = V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.FEE, master, hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), -239, 1L, 1L, hex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5"))
   assertArrayEquals(hex("6606477fc18461092b93eed4bfbc67e149209062d8d479aa760a7a84e57d3bac8f78c93be70cb56b446a82b43665de20"), actual)
   assertArrayEquals(ByteArray(32), master)
  }
 }
 @Test fun invalidContextStillWipesMaster() {
  val master = ByteArray(32) { 9 }
  val error = runCatching { V5R2Kdf.deriveAndWipe(V5R2Kdf.Material.FEE, master, ByteArray(32), 42, 0, 0) }.exceptionOrNull()
  assertTrue(error is IllegalArgumentException)
  assertArrayEquals(ByteArray(32), master)
 }
}
