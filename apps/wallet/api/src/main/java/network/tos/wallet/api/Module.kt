package network.tos.wallet.api

import org.koin.dsl.module

val apiModule = module {
    single(createdAtStart = true) { API(get(), get()) }
}