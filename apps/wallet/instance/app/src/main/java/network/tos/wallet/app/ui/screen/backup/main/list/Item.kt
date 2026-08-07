package network.tos.wallet.app.ui.screen.backup.main.list

import network.tos.wallet.app.helper.DateHelper
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.backup.entities.BackupEntity
import java.util.Locale

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_HEADER = 1
        const val TYPE_RECOVERY_PHRASE = 2
        const val TYPE_BACKUP = 3
        const val TYPE_SPACE = 4
        const val TYPE_MANUAL_BACKUP = 5
        const val TYPE_MANUAL_ACCENT_BACKUP = 6
        const val TYPE_ALERT = 7
    }

    data object Header: Item(TYPE_HEADER)

    data object RecoveryPhrase: Item(TYPE_RECOVERY_PHRASE)

    data object Space: Item(TYPE_SPACE)

    data object ManualBackup: Item(TYPE_MANUAL_BACKUP)

    data object ManualAccentBackup: Item(TYPE_MANUAL_ACCENT_BACKUP)

    data class Alert(
        val balanceFormat: CharSequence,
        val red: Boolean,
    ): Item(TYPE_ALERT)

    data class Backup(
        val position: ListCell.Position,
        val entity: BackupEntity,
        val locale: Locale,
    ): Item(TYPE_BACKUP) {

        val date: String by lazy {
            DateHelper.timestampToDateString(entity.date / 1000, locale)
        }
    }

}