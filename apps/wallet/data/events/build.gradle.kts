plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
}

android {
    namespace = Build.namespacePrefix("wallet.data.events")
}

dependencies {
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.rates))
    implementation(project(ProjectModules.Wallet.Data.collectibles))
    implementation(project(ProjectModules.Wallet.Data.staking))
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.icu))
    implementation(project(ProjectModules.Lib.security))
    implementation(project(ProjectModules.Lib.sqlite))

    implementation(libs.compose.paging)

    implementation(libs.androidX.room.runtime)
    implementation(libs.androidX.room.ktx)
    ksp(libs.androidX.room.compiler)
}
