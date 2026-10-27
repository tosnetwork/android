import com.android.build.gradle.internal.dsl.NdkOptions


plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

val feeStateTargets = listOf("aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android")
val feeStateManifest = layout.projectDirectory.file("src/main/rust/fee-state-bundle/Cargo.toml")
val feeStateOutput = layout.buildDirectory.dir("rust-fee-state")

val proofAbis = providers.gradleProperty("tosProofAbis").orElse("arm64-v8a,armeabi-v7a,x86,x86_64").get().split(",").toSet()
require(proofAbis.isNotEmpty() && proofAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
require(gradle.startParameter.taskNames.none { it.contains("Release", ignoreCase = true) } || proofAbis.size == 4) {
    "Release proof packaging requires all four ABIs"
}
require(Build.minSdkVersion >= 24) { "Pinned proof libraries require Android API24 or later" }
val proofOutput = layout.buildDirectory.dir("generated/quantum-proof-jni")

android {
    namespace = Build.namespacePrefix("security")
    compileSdk = Build.compileSdkVersion
    ndkVersion = Build.ndkVersion

    defaultConfig {
        minSdk = Build.minSdkVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { arguments += "-DTOS_FEE_STATE_TARGET_DIR=${feeStateOutput.get().asFile.absolutePath}" }
        }

        ndk {
            // Never package stale generated libraries for ABIs omitted from this build.
            abiFilters += proofAbis
            debugSymbolLevel = NdkOptions.DebugSymbolLevel.SYMBOL_TABLE.toString()
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(proofOutput)

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

val proofBuilds = proofAbis.map { abi ->
    tasks.register<Exec>("buildQuantumProof${abi.replace("-", "").replace("_", "")}") {
        workingDir(rootProject.projectDir)
        inputs.file(rootProject.file("scripts/build_quantum_proof.py"))
        inputs.file(rootProject.file("scripts/quantum-proof-revision.txt"))
        inputs.file(file("src/main/cpp/wallet_receipt_jni.cpp"))
        outputs.file(proofOutput.map { it.file("$abi/libtosproofverify.so") })
        outputs.file(proofOutput.map { it.file("$abi/libtoswalletreceipt.so") })
        outputs.file(proofOutput.map { it.file("$abi/PROVENANCE.json") })
        // CMake checks the pinned source graph and repairs missing/corrupt outputs.
        outputs.upToDateWhen { false }
        commandLine("python3", rootProject.file("scripts/build_quantum_proof.py").absolutePath,
            "--ndk", File(android.sdkDirectory, "ndk/${Build.ndkVersion}").absolutePath,
            "--output", proofOutput.get().asFile.absolutePath, "--abi", abi)
    }
}
tasks.named("preBuild") { dependsOn(proofBuilds) }

// Aggregate tasks such as `assemble` also select release variants. A graph
// dependency runs even when the release task itself is up-to-date or cached.
val verifyFullQuantumAbis = tasks.register("verifyFullQuantumAbis") {
    val selectedAbiCount = proofAbis.size
    inputs.property("selectedAbiCount", selectedAbiCount)
    doLast { require(selectedAbiCount == 4) { "Release proof packaging requires all four ABIs" } }
}
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && !name.contains("Clean", ignoreCase = true)) {
        dependsOn(verifyFullQuantumAbis)
    }
}


val feeStateBuilds = feeStateTargets.mapIndexed { index, target ->
    tasks.register<Exec>("buildQuantumFeeState$index") {
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
    androidTestImplementation(libs.androidX.test)
    androidTestImplementation(libs.androidX.test.core)
    androidTestImplementation("androidx.test:runner:1.7.0")

    implementation(libs.kotlinX.coroutines.android)
    implementation(libs.kotlinX.serialization.json)
    implementation(libs.androidX.security)
    implementation(libs.bcprovjdk)
    implementation(project(ProjectModules.Lib.extensions))
    compileOnly(fileTree("libs") {
        include("*.aar")
    })

}
