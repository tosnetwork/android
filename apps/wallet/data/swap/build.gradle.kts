plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.swap")
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.kotlinX.coroutines.guava)
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.icu))
}
