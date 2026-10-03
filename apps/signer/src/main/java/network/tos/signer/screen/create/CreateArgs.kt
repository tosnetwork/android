package network.tos.signer.screen.create

import androidx.lifecycle.SavedStateHandle
import network.tos.blockchain.TosV1Mnemonic

internal class CreateArgs(
    private val savedStateHandle: SavedStateHandle
) {

    private companion object {
        private const val NAME_KEY = "name"
        private const val PASSWORD_KEY = "password"
        private const val MNEMONIC_KEY = "mnemonic"
        private const val PROFILE_KEY = "mnemonic_profile"
    }

    var name: String?
        get() = savedStateHandle[NAME_KEY]
        set(value) = savedStateHandle.set(NAME_KEY, value)

    var password: CharArray?
        get() = savedStateHandle[PASSWORD_KEY]
        set(value) = savedStateHandle.set(PASSWORD_KEY, value)

    var mnemonic: List<String>?
        get() = savedStateHandle[MNEMONIC_KEY]
        set(value) = savedStateHandle.set(MNEMONIC_KEY, value)

    var mnemonicProfile: TosV1Mnemonic.Profile?
        get() = savedStateHandle.get<String>(PROFILE_KEY)?.let(TosV1Mnemonic.Profile::valueOf)
        set(value) = savedStateHandle.set(PROFILE_KEY, value?.name)

}
