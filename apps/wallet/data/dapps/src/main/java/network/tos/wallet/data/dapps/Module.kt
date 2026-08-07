package network.tos.wallet.data.dapps

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val dAppsModule = module {
    singleOf(::DAppsRepository)
}