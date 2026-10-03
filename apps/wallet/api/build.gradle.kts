import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

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
    sourceSets.getByName("test").resources.srcDir(rootProject.file("scripts/fixtures"))
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
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.2.1")
    // Use the real org.json in unit tests (Android's bundled org.json is stubbed/not-mocked).
    testImplementation("org.json:json:20231013")
}

// A real-node mapper run must not reuse a previously skipped offline test result.
tasks.withType<Test>().configureEach {
    inputs.property("tosTestRpc", providers.environmentVariable("TOS_TEST_RPC").orElse(""))
    inputs.property("tosTestAddress", providers.environmentVariable("TOS_TEST_ADDRESS").orElse(""))
}
