package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import network.tos.wallet.app.ui.screen.pq.V5R2RecoveryInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V5R2RecoveryInputTest {
    @Test fun parseAndFailureConsumeAllCharacters() {
        val chars = "09".repeat(32).toCharArray()
        val master = V5R2RecoveryInput.rawMasterAndWipe(chars)
        assertArrayEquals(ByteArray(32) { 9 }, master)
        assertTrue(chars.all { it == '\u0000' }); master.fill(0)
        val invalid = ("09".repeat(31) + "0z").toCharArray()
        assertTrue(runCatching { V5R2RecoveryInput.rawMasterAndWipe(invalid) }.isFailure)
        assertTrue(invalid.all { it == '\u0000' })
    }
    @Test fun cancelledScopeNeverRunsAndStillClearsMaster() = runBlocking {
        val owner = Job().apply { cancel() }
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val master = ByteArray(32) { 9 }
        var ran = false
        V5R2RecoveryInput.launchConsumed(scope, master) { ran = true }.join()
        assertFalse(ran); assertArrayEquals(ByteArray(32), master)
    }
}
