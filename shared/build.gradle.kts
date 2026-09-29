import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val webrtcNatives = when {
    OperatingSystem.current().isWindows -> "windows-x86_64"
    OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
    else -> "linux-x86_64"
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    androidLibrary {
        namespace = "com.mertbek.sharescreen.shared"
        compileSdk = 36
        minSdk = 29
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    wasmJs {
        browser()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.resources)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.navigation.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.webrtc.java)
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtcJava.get()}:$webrtcNatives")
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.mertbek.sharescreen.resources"
    generateResClass = always
}
