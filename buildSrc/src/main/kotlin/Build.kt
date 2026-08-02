import org.gradle.api.JavaVersion

object Build {
    const val compileSdkVersion = 36
    const val minSdkVersion = 24
    const val ndkVersion = "27.0.12077973"

    val compileJavaVersion = JavaVersion.VERSION_17

    fun namespacePrefix(name: String) = "com.tonapps.$name"
}
