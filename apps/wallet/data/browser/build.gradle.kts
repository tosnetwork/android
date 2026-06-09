plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.browser")
}

dependencies {
    implementation(libs.okhttp)

    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.account))

    implementation(project(ProjectModules.Lib.network))
    implementation(project(ProjectModules.Lib.extensions))
}

