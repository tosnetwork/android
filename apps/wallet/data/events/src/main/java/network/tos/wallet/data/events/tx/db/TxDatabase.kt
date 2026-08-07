package network.tos.wallet.data.events.tx.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import network.tos.wallet.api.entity.value.ValueConverters

@Database(entities = [TxRecordEntity::class], version = 1)
@TypeConverters(TxConverters::class, ValueConverters::class)
internal abstract class TxDatabase: RoomDatabase() {

    companion object {

        fun instance(context: Context): TxDatabase {
            return Room.databaseBuilder(
                context,
                TxDatabase::class.java,
                "tx_database"
            ).build()
        }
    }

    abstract fun txRecordDao(): TxRecordDao
}