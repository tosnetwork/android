import org.gradle.api.JavaVersion

object Build {
    const val compileSdkVersion = 36
    const val minSdkVersion = 24
    const val ndkVersion = "29.0.13599879"

    val compileJavaVersion = JavaVersion.VERSION_17

    fun namespacePrefix(name: String) = "com.tonapps.$name"
}
