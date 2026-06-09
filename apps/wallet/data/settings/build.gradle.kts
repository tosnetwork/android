plugins {
    id("com.tonapps.wallet.data")
}

android {
    namespace = Build.namespacePrefix("wallet.data.settings")
}

dependencies {
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.rn))
    implementation(project(ProjectModules.Wallet.localization))
}
