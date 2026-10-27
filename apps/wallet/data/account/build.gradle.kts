plugins {
    id("network.tos.wallet.data")
    id("kotlin-parcelize")
    kotlin("plugin.serialization")
}

android {
    namespace = Build.namespacePrefix("wallet.data.account")
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets["androidTest"].assets.srcDir(rootProject.file("scripts/fixtures"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("lib/security/src/androidTest/assets"))
    // Reuse the captured native proofs without duplicating frozen binary material.
    sourceSets["androidTest"].assets.srcDir(rootProject.file("apps/wallet/instance/main/src/androidTest/assets/quantum-live-genesis"))
}

dependencies {
    androidTestImplementation(libs.androidX.test)
    androidTestImplementation(libs.androidX.test.core)
    androidTestImplementation("androidx.test:runner:1.7.0")
    implementation(libs.okhttp)
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
