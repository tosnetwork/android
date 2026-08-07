package network.tos.signer.screen.key

import android.content.Context
import androidx.lifecycle.ViewModel
import network.tos.signer.core.repository.KeyRepository
import network.tos.signer.password.Password
import network.tos.signer.vault.SignerVault
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import network.tos.security.vault.safeArea

class KeyViewModel(
    private val id: Long,
    private val keyRepository: KeyRepository,
    private val vault: SignerVault,
): ViewModel() {

    val keyEntity = keyRepository.getKey(id).filterNotNull()

    fun delete(context: Context) = Password.authenticate(context).safeArea {
        vault.delete(it, id)
        keyRepository.deleteKey(id)
    }.take(1)

    fun getRecoveryPhrase(context: Context) = Password.authenticate(context).safeArea {
        vault.getMnemonic(it, id).toTypedArray()
    }.take(1)
}