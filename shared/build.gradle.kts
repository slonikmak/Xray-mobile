plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
}

kotlin {
    android {
        namespace = "dev.xray.mobile.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
        withHostTest {}
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("io.ktor:ktor-client-core:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.1")
        }
        getByName("desktopMain").dependencies { implementation("io.ktor:ktor-client-okhttp:3.6.1") }
    }
}

compose.resources { packageOfResClass = "dev.xray.mobile.resources" }

// shortcut: Compose 1.7 omits host-test lint dependencies; remove after upgrading Compose.
tasks.matching { it.name == "generateAndroidHostTestLintModel" || it.name == "lintAnalyzeAndroidHostTest" }.configureEach {
    dependsOn("generateResourceAccessorsForCommonTest", "generateResourceAccessorsForAndroidHostTest")
}
