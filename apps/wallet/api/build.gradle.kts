import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    kotlin("plugin.serialization")
}

android {
    namespace = Build.namespacePrefix("wallet.api")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.kotlinX.serialization.core)
    implementation(libs.kotlinX.serialization.json)
    implementation(libs.kotlinX.coroutines.guava)
    implementation(libs.koin.core)
    implementation(project(ProjectModules.Module.tonApi))
    implementation(project(ProjectModules.Lib.network))
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.extensions))
    implementation(project(ProjectModules.Lib.icu))
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    implementation(libs.androidX.room.runtime)
    implementation(libs.androidX.room.ktx)
    ksp(libs.androidX.room.compiler)

    testImplementation(libs.junit)
    // Use the real org.json in unit tests (Android's bundled org.json is stubbed/not-mocked).
    testImplementation("org.json:json:20231013")
}
