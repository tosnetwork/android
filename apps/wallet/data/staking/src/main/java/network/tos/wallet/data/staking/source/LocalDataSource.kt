package network.tos.wallet.data.staking.source

import android.content.Context
import network.tos.extensions.toByteArray
import network.tos.extensions.toListParcel
import network.tos.extensions.toParcel
import network.tos.wallet.data.core.BlobDataSource
import network.tos.wallet.data.staking.entities.PoolInfoEntity
import network.tos.wallet.data.staking.entities.StakingEntity
import java.util.concurrent.TimeUnit

internal class LocalDataSource(context: Context): BlobDataSource<StakingEntity>(
    context = context,
    path = "staking"
) {

    override fun onMarshall(data: StakingEntity) = data.toByteArray()

    override fun onUnmarshall(bytes: ByteArray) = bytes.toParcel<StakingEntity>()
}