plugins {
    id("network.tos.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.backup")
}

dependencies {
    implementation(project(ProjectModules.Lib.sqlite))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Wallet.Data.rn))
}

