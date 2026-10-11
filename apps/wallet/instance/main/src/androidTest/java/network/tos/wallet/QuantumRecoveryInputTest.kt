package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import network.tos.wallet.app.ui.screen.pq.QuantumRecoveryInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantumRecoveryInputTest {
    @Test fun parseAndFailureConsumeAllCharacters() {
        val chars = "09".repeat(32).toCharArray()
        val master = QuantumRecoveryInput.rawMasterAndWipe(chars)
        assertArrayEquals(ByteArray(32) { 9 }, master)
        assertTrue(chars.all { it == '\u0000' }); master.fill(0)
        val invalid = ("09".repeat(31) + "0z").toCharArray()
        assertTrue(runCatching { QuantumRecoveryInput.rawMasterAndWipe(invalid) }.isFailure)
        assertTrue(invalid.all { it == '\u0000' })
    }
    @Test fun cancelledScopeNeverRunsAndStillClearsMaster() = runBlocking {
        val owner = Job().apply { cancel() }
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val master = ByteArray(32) { 9 }
        var ran = false
        QuantumRecoveryInput.launchConsumed(scope, master) { ran = true }.join()
        assertFalse(ran); assertArrayEquals(ByteArray(32), master)
    }
    @Test fun nativeMnemonicMatchesFrozenMasterAndCancelledInputsAreCleared() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon amateur".uppercase().replace(" ", "\u2003\u0085\t").toCharArray()
        val password = "".toCharArray()
        var captured: ByteArray? = null
        var error: Exception? = null
        QuantumRecoveryInput.launchNativeConsumed(scope, words, password, { error = it }) { master ->
            assertEquals("cc97dcca0bed763026ad0174ad4c38a057fca97863005b181e11f9f0a0c39bc6", master.joinToString("") { "%02x".format(it.toInt() and 255) })
            captured = master
        }.join()
        assertNull(error); assertNotNull(captured); assertArrayEquals(ByteArray(32), captured)
        assertTrue(words.all { it == '\u0000' }); assertTrue(password.all { it == '\u0000' })
        val owner = Job().apply { cancel() }; val cancelled = CoroutineScope(owner + Dispatchers.Default)
        val pendingWords = "PUBLIC invalid test".toCharArray(); val pendingPassword = "PUBLIC password".toCharArray()
        QuantumRecoveryInput.launchNativeConsumed(cancelled, pendingWords, pendingPassword, { fail("cancelled error callback") }) { fail("cancelled action ran") }.join()
        assertTrue(pendingWords.all { it == '\u0000' }); assertTrue(pendingPassword.all { it == '\u0000' })
        val invalid = "not a native phrase".toCharArray(); val invalidPassword = "PUBLIC invalid password".toCharArray()
        var refused = false
        QuantumRecoveryInput.launchNativeConsumed(scope, invalid, invalidPassword, { refused = true }) { fail("invalid action ran") }.join()
        assertTrue(refused); assertTrue(invalid.all { it == '\u0000' }); assertTrue(invalidPassword.all { it == '\u0000' })
        scope.cancel()
    }
}
