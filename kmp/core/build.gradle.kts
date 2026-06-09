plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

version = "1.0"

kotlin {
    androidTarget()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    macosX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    macosArm64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {

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


android {
    compileSdk = Build.compileSdkVersion
    namespace = "com.tonkeeper.core"
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = Build.minSdkVersion
    }
    compileOptions {
        sourceCompatibility = Build.compileJavaVersion
        targetCompatibility = Build.compileJavaVersion
    }
}
