package network.tos.wallet.app.core

import androidx.annotation.StringRes
import network.tos.extensions.ErrorForUserException
import network.tos.wallet.api.SendBlockchainState
import network.tos.wallet.localization.Localization

sealed class SendBlockchainException(
    @StringRes stringRes: Int,
    cause: Throwable? = null
): ErrorForUserException(stringRes = stringRes, cause = cause) {

    companion object {

        fun fromState(state: SendBlockchainState): SendBlockchainException {
            return when (state) {
                SendBlockchainState.STATUS_ERROR -> SendBlockchainStatusException
                SendBlockchainState.UNKNOWN_ERROR -> SendBlockchainErrorException
                else -> SendBlockchainErrorException
            }
        }
    }

    data object SendBlockchainStatusException: SendBlockchainException(Localization.sending_error) {
        private fun readResolve(): Any = SendBlockchainStatusException
    }

    data object SendBlockchainErrorException: SendBlockchainException(Localization.error) {
        private fun readResolve(): Any = SendBlockchainStatusException
    }

}