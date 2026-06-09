plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.plugins")
}

dependencies {
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Wallet.Data.core))
}



