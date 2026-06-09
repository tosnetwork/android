import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = Build.namespacePrefix("signer")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        applicationId = Build.namespacePrefix("signer")
        minSdk = 26
        targetSdk = Build.compileSdkVersion
        versionCode = 23
        versionName = "0.2.3"
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "ENVIRONMENT", "\"\"")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "ENVIRONMENT", "\"dev\"")
        }
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidX.core)
    implementation(libs.androidX.appCompat)
    implementation(libs.androidX.activity)
    implementation(libs.androidX.fragment)
    implementation(libs.androidX.recyclerView)
    implementation(libs.androidX.viewPager2)
    implementation(libs.androidX.splashscreen)

    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.cameraX.base)
    implementation(libs.cameraX.core)
    implementation(libs.cameraX.lifecycle)
    implementation(libs.cameraX.view)
    implementation(libs.androidX.security)
    implementation(libs.androidX.constraintlayout)
    implementation(libs.androidX.lifecycleSavedState)
    implementation(project(ProjectModules.Lib.blockchain))
    implementation(project(ProjectModules.Lib.extensions))

    implementation(libs.kotlinX.coroutines.guava)

    implementation(project(ProjectModules.UIKit.core)) {
        exclude("com.airbnb.android", "lottie")
        exclude("com.facebook.fresco", "fresco")
    }

    implementation(project(ProjectModules.Lib.qr))
    implementation(project(ProjectModules.Lib.security))
    implementation(project(ProjectModules.Lib.icu))
    implementation(libs.koin.core)
}

