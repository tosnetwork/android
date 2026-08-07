package network.tos.signer

import network.tos.signer.core.di.coreModule
import network.tos.signer.core.repository.KeyRepository
import network.tos.signer.screen.change.ChangeViewModel
import network.tos.signer.screen.create.CreateViewModel
import network.tos.signer.screen.key.KeyViewModel
import network.tos.signer.screen.main.MainViewModel
import network.tos.signer.screen.root.RootViewModel
import network.tos.signer.screen.sign.SignViewModel
import network.tos.signer.vault.SignerVault
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val koinModel = module {
    single(createdAtStart = false) { SignerVault(androidContext()) }

    includes(coreModule)

    viewModel { RootViewModel(get(), get()) }
    viewModel { parameters -> CreateViewModel(import = parameters.get(), get(), get(), get()) }
    viewModel { MainViewModel(get()) }
    viewModel { parameters -> KeyViewModel(id = parameters.get(), get(), get()) }
    viewModel { parameters -> SignViewModel(
        id = parameters.get(),
        unsignedBody = parameters.get(),
        v = parameters.get(),
        seqno = parameters.get(),
        network = parameters.get(),
        repository = get<KeyRepository>(),
        vault = get<SignerVault>()
    ) }
    viewModel { ChangeViewModel(get(), get()) }
}