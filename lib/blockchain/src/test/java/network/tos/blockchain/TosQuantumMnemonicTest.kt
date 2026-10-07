package network.tos.blockchain
import org.junit.Assert.*
import org.junit.Test
class TosQuantumMnemonicTest {
 @Test fun nativeMasterVectorsAndPasswordWipe() {
  run {
   val password = "".toCharArray()
   val master = TosQuantumMnemonic.masterAndWipePassword("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon amateur".split(" "), password)
   assertEquals("cc97dcca0bed763026ad0174ad4c38a057fca97863005b181e11f9f0a0c39bc6", master.joinToString("") { "%02x".format(it.toInt() and 255) })
   assertTrue(password.all { it == '\u0000' })
  }
  run {
   val password = " public test password ".toCharArray()
   val master = TosQuantumMnemonic.masterAndWipePassword("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon chicken".split(" "), password)
   assertEquals("5baee244077bdb19c1d9a4670a7c2a3c6dc88815a039eb9c75f3e4d7185f6c39", master.joinToString("") { "%02x".format(it.toInt() and 255) })
   assertTrue(password.all { it == '\u0000' })
  }
 }
 @Test fun invalidPhraseStillClearsPassword() {
  val password = "secret".toCharArray()
  assertThrows(IllegalArgumentException::class.java) { TosQuantumMnemonic.masterAndWipePassword(List(12) { "abandon" }, password) }
  assertTrue(password.all { it == '\u0000' })
 }
}
