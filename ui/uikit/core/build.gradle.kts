plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uikit"
    compileSdk = Build.compileSdkVersion
    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(ProjectModules.UIKit.color))
    api(project(ProjectModules.UIKit.icon))
    api(project(ProjectModules.UIKit.list))
    api(project(ProjectModules.Module.blur))
    api(project(ProjectModules.Module.shimmer))

    implementation(project(ProjectModules.KMP.ui))

    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.androidX.core)
    implementation(libs.androidX.webkit)
    implementation(libs.androidX.activity)
    implementation(libs.androidX.fragment)
    implementation(libs.androidX.appCompat)
    implementation(libs.androidX.splashscreen)
    implementation(libs.flexbox)
    implementation(libs.material)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundationLayout)
    implementation(libs.compose.ui)
}
