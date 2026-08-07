package network.tos.signer.vault

import android.content.Context
import network.tos.signer.extensions.securePrefs
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.mnemonic.Mnemonic
import network.tos.security.clear
import network.tos.security.safeDestroy
import network.tos.security.tryCallGC
import network.tos.security.vault.Vault
import network.tos.security.vault.getString
import network.tos.security.vault.putString
import javax.crypto.SecretKey

class SignerVault(
    context: Context,
    name: String,
): Vault(context.securePrefs(name)) {

    constructor(context: Context): this(context, "signer")

    suspend fun setMnemonic(secret: SecretKey, id: Long, mnemonic: List<String>) {
        putString(secret, id, mnemonic.joinToString(","))
        secret.safeDestroy()
    }

    suspend fun getMnemonic(secret: SecretKey, id: Long): List<String> {
        val list = getString(secret, id).split(",")
        secret.safeDestroy()
        return list
    }

    suspend fun getPrivateKey(secret: SecretKey, id: Long): PrivateKeyEd25519 {
        val mnemonic = getMnemonic(secret, id)
        val seed = Mnemonic.toSeed(mnemonic)
        val privateKey = PrivateKeyEd25519(seed)
        seed.clear()
        tryCallGC()
        return privateKey
    }
}