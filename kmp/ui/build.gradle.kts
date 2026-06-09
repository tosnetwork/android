plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.multiplatform.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

version = "1.0"

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.compose.multiplatform.runtime)
                implementation(libs.compose.multiplatform.foundation)
                implementation(libs.compose.multiplatform.ui)
                implementation(libs.compose.multiplatform.ui.util)
                implementation(libs.compose.multiplatform.material)
                implementation(libs.compose.multiplatform.material3)
                implementation(libs.compose.multiplatform.resources)
                implementation(libs.coil.compose)

                implementation(libs.kotlinX.collections.immutable)
            }
        }

        androidMain {
            dependencies {

            }
        }

        iosMain {
            dependencies {

            }
        }
    }
}


compose.resources {
    publicResClass = false
    packageOfResClass = "ui.theme.resources"
    generateResClass = auto
}

android {
    compileSdk = Build.compileSdkVersion
    namespace = "com.tonkeeper.ui"
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = Build.minSdkVersion
    }
    compileOptions {
        sourceCompatibility = Build.compileJavaVersion
        targetCompatibility = Build.compileJavaVersion
    }
}
