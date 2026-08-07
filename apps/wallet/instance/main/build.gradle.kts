@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
    id("androidx.baselineprofile")
}

val isCI = project.hasProperty("android.injected.signing.store.file")
var isAPK = gradle.startParameter.projectProperties["isApk"]?.toBoolean() ?: false

android {
    namespace = Build.namespacePrefix("wallet")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        applicationId = "network.tos.wallet"
        minSdk = Build.minSdkVersion
        targetSdk = Build.compileSdkVersion
        versionCode = 2

        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "version"

    productFlavors {
        create("default") {
            dimension = "version"
        }
        create("site") {
            dimension = "version"
            matchingFallbacks += listOf("default")
        }
        create("uk") {
            dimension = "version"
            applicationIdSuffix = ".uk"
            matchingFallbacks += listOf("default")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (isCI) {
                signingConfig = signingConfigs.getByName("release")
                manifestPlaceholders += if (isAPK) {
                    mapOf("build_type" to "site")
                } else {
                    mapOf("build_type" to "google_play")
                }
            } else {
                manifestPlaceholders += mapOf("build_type" to "manual")
            }
        }

        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders += mapOf("build_type" to "internal_debug")
        }
    }

    experimentalProperties["android.experimental.art-profile-r8-rewriting"] = true
    experimentalProperties["android.experimental.r8.dex-startup-optimization"] = true

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

baselineProfile {
    saveInSrc = true
    dexLayoutOptimization = true
    mergeIntoMain = true
    baselineProfileRulesRewrite = true
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(ProjectModules.Wallet.app))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidX.test)
    androidTestImplementation(libs.androidX.test.core)
    androidTestImplementation(libs.androidX.test.espresso)
    androidTestImplementation(libs.androidX.test.uiautomator)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(project(ProjectModules.Lib.blockchain))
    androidTestImplementation(project(ProjectModules.Lib.icu))
    androidTestImplementation(project(ProjectModules.Lib.qr))
    androidTestImplementation(project(ProjectModules.Lib.security))
    androidTestImplementation(libs.zxing)
    androidTestImplementation(project(ProjectModules.UIKit.core))
    androidTestImplementation(project(ProjectModules.Wallet.Data.account))
    androidTestImplementation(project(ProjectModules.Wallet.Data.passcode))
    androidTestImplementation(project(ProjectModules.Wallet.api))

    implementation(libs.androidX.profileinstaller)
    baselineProfile(project(":baselineprofile:main"))

    debugImplementation(libs.leakcanary)
}
