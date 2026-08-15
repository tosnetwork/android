import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = Build.namespacePrefix("agentcommerce")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // The projection module is dependency-free. JSON parsing of the shared
    // vectors is used only by the tests, so kotlinx-serialization stays a test
    // dependency and the production code carries no serialization runtime.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinX.serialization.json)
}
