plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.tokens")
}

dependencies {
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.rates))
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.icu))
}
