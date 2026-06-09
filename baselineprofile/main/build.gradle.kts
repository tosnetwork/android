@file:Suppress("UnstableApiUsage")
import com.android.build.api.dsl.ManagedVirtualDevice
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.kotlin)
    alias(libs.plugins.android.baselineprofile)
}

val isCI = project.hasProperty("android.injected.signing.store.file")

android {
    namespace = Build.namespacePrefix("main.baselineprofile")
    compileSdk = Build.compileSdkVersion

    defaultConfig {
        testInstrumentationRunnerArguments += mapOf("suppressErrors" to "EMULATOR")
        minSdk = 28
        targetSdk = Build.compileSdkVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = Build.compileJavaVersion
        targetCompatibility = Build.compileJavaVersion
    }

    flavorDimensions += listOf("version")

    productFlavors {
        create("default") { dimension = "version" }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions.managedDevices.devices {
        create<ManagedVirtualDevice>("pixel6Api33") {
            device = "Pixel 6"
            apiLevel = 33
            systemImageSource = "google"
        }
    }

    targetProjectPath = ":apps:wallet:instance:main"

    experimentalProperties["android.experimental.self-instrumenting"] = true
    experimentalProperties["android.experimental.testOptions.managedDevices.setupTimeoutMinutes"] = 20
    experimentalProperties["android.experimental.androidTest.numManagedDeviceShards"] = 1
    experimentalProperties["android.experimental.testOptions.managedDevices.maxConcurrentDevices"] = 1
    experimentalProperties["android.experimental.testOptions.managedDevices.emulator.showKernelLogging"] = true
    if (isCI) {
        experimentalProperties["android.testoptions.manageddevices.emulator.gpu"] = "swiftshader_indirect"
    }
}

dependencies {
    implementation(libs.androidX.test.core)
    implementation(libs.androidX.test.espresso)
    implementation(libs.androidX.test.uiautomator)
    implementation(libs.androidX.benchmark)
}

baselineProfile {
    // managedDevices += "pixel6Api33"
    // useConnectedDevices = false
    enableEmulatorDisplay = !isCI
}


androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        val testedApks = v.testedApks.map {
            artifactsLoader.load(it)?.applicationId ?: "com.ton_keeper"
        }
        v.instrumentationRunnerArguments.put("targetAppId", testedApks)
    }
}