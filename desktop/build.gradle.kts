import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")
    sourceSets {
        getByName("desktopMain") {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("com.google.code.gson:gson:2.11.0")
                implementation("net.java.dev.jna:jna-platform:5.17.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}

val runtimeRoot = layout.buildDirectory.dir("xrayResources")
val runtimeExe = runtimeRoot.map { it.file("common/runtime/xray") }
val fetchDesktopXray = tasks.register("fetchDesktopXray") {
    outputs.file(runtimeExe)
    onlyIf { System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("aarch64", "arm64") }
    doLast {
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        val archive = temporaryDir.resolve("xray.zip")
        val extracted = temporaryDir.resolve("xray")
        val output = runtimeExe.get().asFile
        val connection = URI("https://github.com/XTLS/Xray-core/releases/download/v26.10.31/Xray-macos-arm64-v8a.zip").toURL().openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.getInputStream().use { input ->
            archive.outputStream().use { destination ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 64L * 1024 * 1024) { "Xray archive is too large" }
                    destination.write(buffer, 0, count)
                }
            }
        }
        check(digest(archive) == "0a05dca3bd311b0bc5a89c7ce56350cab1e829e131e4b2bcd48eb304fac07e9a") { "Xray archive checksum mismatch" }
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("xray"))
            zip.getInputStream(entry).use { input -> extracted.outputStream().use { input.copyTo(it) } }
        }
        check(digest(extracted) == "9ddc3b0d912a8539285918e4fead4b60bbc449acdb639450ba068bc7efb983b7") { "Xray executable checksum mismatch" }
        output.parentFile.mkdirs()
        extracted.copyTo(output, overwrite = true)
        check(output.setExecutable(true, false)) { "Cannot mark Xray executable" }
    }
}

val supervisorSource = layout.projectDirectory.file("src/macosMain/native/xray-supervisor.c")
val supervisor = runtimeRoot.map { it.file("common/runtime/xray-supervisor") }
val compileMacSupervisor = tasks.register<Exec>("compileMacSupervisor") {
    inputs.file(supervisorSource)
    outputs.file(supervisor)
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    doFirst { supervisor.get().asFile.parentFile.mkdirs() }
    commandLine("/usr/bin/clang", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", supervisorSource.asFile.absolutePath, "-o", supervisor.get().asFile.absolutePath)
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchDesktopXray, compileMacSupervisor) }
tasks.matching { it.name == "desktopTest" }.configureEach { dependsOn(fetchDesktopXray, compileMacSupervisor) }

compose.desktop {
    application {
        mainClass = "dev.xray.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(runtimeRoot)
            modules("java.net.http")
        }
    }
}
