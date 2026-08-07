package network.tos.wallet.data.collectibles

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val collectiblesModule = module {
    singleOf(::CollectiblesRepository)
}