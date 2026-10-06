import com.android.build.gradle.internal.dsl.NdkOptions


plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

val feeStateTargets = listOf("aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android")
val feeStateManifest = layout.projectDirectory.file("src/main/rust/fee-state-bundle/Cargo.toml")
val feeStateOutput = layout.buildDirectory.dir("rust-fee-state")

android {
    namespace = Build.namespacePrefix("security")
    compileSdk = Build.compileSdkVersion
    ndkVersion = Build.ndkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { arguments += "-DTOS_FEE_STATE_TARGET_DIR=${feeStateOutput.get().asFile.absolutePath}" }
        }

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

val feeStateBuilds = feeStateTargets.mapIndexed { index, target ->
    tasks.register<Exec>("buildV5R2FeeState$index") {
        workingDir(file("src/main/rust/fee-state-bundle"))
        inputs.dir("src/main/rust/fee-state-bundle")
        outputs.file(feeStateOutput.map { it.file("$target/release/liblms_fee_state.a") })
        commandLine("cargo", "build", "--manifest-path", feeStateManifest.asFile.absolutePath,
            "-p", "lms-fee-state", "--release", "--locked", "--target", target,
            "--target-dir", feeStateOutput.get().asFile.absolutePath)
    }
}
tasks.named("preBuild") { dependsOn(feeStateBuilds) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(feeStateBuilds)
}

dependencies {

    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.androidX.security)
    implementation(libs.bcprovjdk)
    implementation(project(ProjectModules.Lib.extensions))
    compileOnly(fileTree("libs") {
        include("*.aar")
    })

}
