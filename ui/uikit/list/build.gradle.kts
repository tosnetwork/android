plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = Build.namespacePrefix("uikit.list")
    compileSdk = Build.compileSdkVersion
    defaultConfig {
        minSdk = Build.minSdkVersion
    }
}

dependencies {
    implementation(libs.androidX.recyclerView)
    implementation(libs.androidX.lifecycle)
}
