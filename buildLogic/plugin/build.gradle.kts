plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:8.12.3")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "com.tonapps.wallet.data"
            implementationClass = "WalletDataPlugin"
        }
    }
}