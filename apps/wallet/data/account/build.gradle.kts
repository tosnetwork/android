plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
    kotlin("plugin.serialization")
}

android {
    namespace = Build.namespacePrefix("wallet.data.account")
}

dependencies {
    implementation(libs.kotlinX.serialization.json)
    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.koin.core)
    implementation(libs.ton.tvm)
    implementation(libs.ton.crypto)
    implementation(libs.ton.tlb)
    implementation(libs.ton.blockTlb)
    implementation(libs.ton.tonapiTl)
    implementation(libs.ton.contract)
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.rn))
    implementation(project(ProjectModules.Wallet.Data.rates))
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Lib.security))
    implementation(project(ProjectModules.Lib.network))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.sqlite))
    implementation(project(ProjectModules.Lib.ledger))
}
