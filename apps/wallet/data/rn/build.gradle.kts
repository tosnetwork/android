plugins {
    id("com.tonapps.wallet.data")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.rn")
}

dependencies {
    api(platform(libs.firebase.bom))
    api(libs.firebase.crashlytics)

    implementation(libs.androidX.biometric)
    implementation(libs.ton.crypto)

    implementation(project(ProjectModules.Lib.sqlite))
    implementation(project(ProjectModules.Lib.security))
    implementation(project(ProjectModules.Lib.extensions))
}


