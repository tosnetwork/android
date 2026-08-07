package network.tos.wallet.app.ui.screen.events.compose.history

import org.junit.Assert.assertEquals
import org.junit.Test
import ui.components.events.UiEvent

class TxUiStateTest {
    @Test
    fun `pending confirmed and failed states map deterministically`() {
        assertEquals(UiEvent.Item.Action.State.Pending, txActionState(inProgress = true, failed = false))
        assertEquals(UiEvent.Item.Action.State.Pending, txActionState(inProgress = true, failed = true))
        assertEquals(UiEvent.Item.Action.State.Success, txActionState(inProgress = false, failed = false))
        assertEquals(UiEvent.Item.Action.State.Failed, txActionState(inProgress = false, failed = true))
    }
}
