package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.security.pq.PqAlgorithm
import network.tos.wallet.data.account.pq.PqWalletRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PqWalletRepositoryTest {
    @Test fun deviceKeystoreBackupsAndCancelledAuthenticationPreserveIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var unlocked = false
        val repository = PqWalletRepository(context) { unlocked }
        val original = repository.list().map { it.id }.toSet()
        val created = mutableListOf<String>()
        val password = "PUBLIC test backup 密码 🌌".toCharArray()
        suspend fun rejected(action: suspend () -> Unit) {
            var refused = false
            try { action() } catch (e: Exception) { refused = true }
            assertTrue("Operation should fail closed", refused)
        }
        try {
            for (algorithm in PqAlgorithm.entries) {
                unlocked = false
                rejected { repository.create("QA cancelled", algorithm, 3) }
                assertEquals(original, repository.list().map { it.id }.toSet())
                unlocked = true
                val record = repository.create("QA device key ${algorithm.id}", algorithm, 3)
                created.add(record.id)
                val address = record.descriptor().address
                val backup = repository.backup(record.id, password)
                assertEquals(121, backup.size)
                unlocked = false
                rejected { repository.backup(record.id, password) }
                rejected { repository.delete(record.id) }
                rejected { repository.sign(record.id, ByteArray(32)) }
                assertTrue(repository.list().any { it.id == record.id })
                unlocked = true
                repository.delete(record.id);created.remove(record.id)
                val restored = repository.restore("QA restored", algorithm, 3, backup, password)
                created.add(restored.id)
                assertArrayEquals(record.publicKey, restored.publicKey)
                assertEquals(address, restored.descriptor().address)
                val count = repository.list().size
                rejected { repository.restore("QA duplicate", algorithm, 3, backup, password) }
                rejected { repository.restore("QA wrong network", algorithm, 4, backup, password) }
                rejected { repository.restore("QA wrong password", algorithm, 3, backup, "wrong password PUBLIC".toCharArray()) }
                assertEquals(count, repository.list().size)
                repository.delete(restored.id);created.remove(restored.id)
                assertEquals(original, repository.list().map { it.id }.toSet())
            }
        } finally {
            unlocked = true
            for (id in created) repository.delete(id)
            password.fill('\u0000')
        }
    }
}
