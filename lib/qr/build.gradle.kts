import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = Build.namespacePrefix("qr")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidX.core)
    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.zxing)

    api(libs.barcode.scanning)
    implementation(libs.cameraX.base)
}