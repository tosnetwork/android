import com.android.build.gradle.internal.dsl.NdkOptions

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = Build.namespacePrefix("security")
    compileSdk = Build.compileSdkVersion
    ndkVersion = Build.ndkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            debugSymbolLevel = NdkOptions.DebugSymbolLevel.SYMBOL_TABLE.toString()
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.androidX.security)
    implementation(project(ProjectModules.Lib.extensions))
    compileOnly(fileTree("libs") {
        include("*.aar")
    })

}
