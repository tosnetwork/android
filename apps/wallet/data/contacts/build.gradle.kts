plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.contacts")
}

dependencies {
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.sqlite))
    implementation(project(ProjectModules.Wallet.Data.rn))
}
