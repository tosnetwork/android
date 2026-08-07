package network.tos.wallet.data.plugins

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val pluginsModule = module {
    singleOf(::PluginsRepository)
}



