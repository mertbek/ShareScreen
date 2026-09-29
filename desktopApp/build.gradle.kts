import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.TaskProvider

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

apply(from = rootProject.file("gradle/server-config.gradle.kts"))

val appVersion = providers.gradleProperty("sharescreen.version").get()
val generateServerConfig = extra["generateServerConfig"] as TaskProvider<*>

kotlin {
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain {
            kotlin.srcDir(generateServerConfig)
        }
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":lan"))
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mertbek.sharescreen.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ShareScreen"
            packageVersion = appVersion
            description = "Share and watch screens over the local network or the internet"
            vendor = "mertbek"
            copyright = "Copyright (c) 2026 mertbek"
            modules("java.instrument", "java.management", "java.naming", "java.prefs", "jdk.crypto.ec", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "ShareScreen"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8f3b6a52-6a0e-4b7d-9b1f-3a52c1d7e4a9"
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "com.mertbek.sharescreen"
                dmgPackageVersion = "1.0.0"
                pkgPackageVersion = "1.0.0"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                packageName = "sharescreen"
                menuGroup = "Network"
                appCategory = "Network"
                debMaintainer = "mertbek"
                shortcut = true
            }
        }
    }
}

val portableZip by tasks.registering(Zip::class) {
    dependsOn("createDistributable")
    val platform = when {
        OperatingSystem.current().isWindows -> "windows"
        OperatingSystem.current().isMacOsX -> "macos"
        else -> "linux"
    }
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("ShareScreen-$appVersion-$platform-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))
}
