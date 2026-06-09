plugins {
    id("com.tonapps.wallet.data")
}

android {
    namespace = Build.namespacePrefix("wallet.data.passcode")
}

dependencies {

    implementation(libs.androidX.biometric)

    implementation(project(ProjectModules.UIKit.core))

    implementation(project(ProjectModules.Wallet.Data.core))
    implementation(project(ProjectModules.Wallet.Data.account))
    implementation(project(ProjectModules.Wallet.Data.settings))
    implementation(project(ProjectModules.Wallet.Data.rn))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.security))
}
