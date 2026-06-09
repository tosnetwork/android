import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("wallet.data.core")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(platform(libs.firebase.bom))
    api(libs.firebase.crashlytics)

    implementation(libs.ton.tvm)
    implementation(libs.ton.crypto)
    implementation(libs.ton.tlb)
    implementation(libs.ton.blockTlb)
    implementation(libs.ton.tonapiTl)
    implementation(libs.ton.contract)
    implementation(libs.koin.core)
    implementation(libs.androidX.biometric)
    implementation(project(ProjectModules.Wallet.api))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.sqlite))
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.UIKit.flag))
}



