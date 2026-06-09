plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    kotlin("plugin.serialization")
}

android {
    namespace = Build.namespacePrefix("extensions")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.kotlinX.serialization.core)
    implementation(libs.kotlinX.serialization.json)
    implementation(libs.koin.core)
    implementation(libs.androidX.core)
    implementation(libs.androidX.security)
    implementation(project(ProjectModules.UIKit.core))
    implementation(project(ProjectModules.Lib.icu))
    implementation(project(ProjectModules.Lib.base64))
    implementation(libs.google.play.installreferrer)
    implementation(libs.google.play.base)
}
