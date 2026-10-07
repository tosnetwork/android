package network.tos.wallet.app.ui.screen.pq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.tos.blockchain.TosV5R2Mnemonic

/** UI-owned secret input. Callers must not retain or mutate supplied buffers after transfer. */
object V5R2RecoveryInput {
    fun rawMasterAndWipe(chars: CharArray): ByteArray {
        try {
            require(chars.size == 64 && chars.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            // Validate the complete input before constructing any secret output.
            return ByteArray(32) { i -> ((chars[i * 2].digitToInt(16) shl 4) or chars[i * 2 + 1].digitToInt(16)).toByte() }
        } finally { chars.fill('\u0000') }
    }
    fun launchConsumed(scope: CoroutineScope, master: ByteArray, action: suspend (ByteArray) -> Unit): Job {
        val job = scope.launch { try { action(master) } finally { master.fill(0) } }
        // Completion registration also fires for a scope cancelled before the body starts.
        job.invokeOnCompletion { master.fill(0) }
        return job
    }
    fun launchNativeConsumed(scope: CoroutineScope, words: CharArray, password: CharArray,
                             onFailure: (Exception) -> Unit, action: suspend (ByteArray) -> Unit): Job {
        val job = scope.launch {
            var master = ByteArray(0)
            try {
                withContext(Dispatchers.IO) {
                    val normalized = words.concatToString().split(Regex("[\\s\\p{Z}\u0085]+")).filter { it.isNotEmpty() }
                    master = TosV5R2Mnemonic.masterAndWipePassword(normalized, password)
                }
                action(master)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailure(e) }
            finally { words.fill('\u0000'); password.fill('\u0000'); master.fill(0) }
        }
        job.invokeOnCompletion { words.fill('\u0000'); password.fill('\u0000') }
        return job
    }
}
