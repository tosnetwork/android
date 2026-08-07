package network.tos.signer.core.di

import network.tos.signer.core.repository.KeyRepository
import network.tos.signer.core.source.SQLSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val coreModule = module {
    single(createdAtStart = true) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    single { SQLSource(get()) }
    single { KeyRepository(get<CoroutineScope>(), get<SQLSource>()) }
}