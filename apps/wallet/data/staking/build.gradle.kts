plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.staking")
}

dependencies {
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.icu))

    implementation(project(ProjectModules.Module.tonApi))

    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.tokens))
}
