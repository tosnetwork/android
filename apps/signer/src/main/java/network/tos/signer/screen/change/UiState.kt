package network.tos.signer.screen.change

import network.tos.signer.SimpleState

sealed class UiState(open val pageIndex: Int) {

    data class Task(
        val state: SimpleState,
        override val pageIndex: Int
    ): UiState(pageIndex)

    data class InputValid(
        val valid: Boolean,
        override val pageIndex: Int
    ): UiState(pageIndex)
}