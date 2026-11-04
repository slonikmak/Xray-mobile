import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("plugin.compose")
    id("com.android.application")
    id("org.jetbrains.compose")
}

val xrayAar = layout.buildDirectory.file("xray/libXray-26.10.31.aar")
val fetchXray = tasks.register("fetchXray") {
    outputs.file(xrayAar)
    doLast {
        val archive = temporaryDir.resolve("libxray-android.zip")
        val target = xrayAar.get().asFile
        val expected = "fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca"
        target.parentFile.mkdirs()
        URI("https://github.com/XTLS/libXray/releases/download/v26.10.31/libxray-android.zip")
            .toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "libXray checksum mismatch" }
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("libxray-android/libXray.aar"))
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        archive.delete()
    }
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "dev.xray.mobile"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.xray.mobile"; minSdk = 26; targetSdk = 35; ndk.abiFilters += "arm64-v8a" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(files(xrayAar).builtBy(fetchXray))
    implementation(compose.runtime)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    implementation("androidx.core:core-ktx:1.19.1")
}
