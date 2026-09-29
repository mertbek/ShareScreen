import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

apply(from = rootProject.file("gradle/server-config.gradle.kts"))

val generateServerConfig = extra["generateServerConfig"] as TaskProvider<*>

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        outputModuleName = "webApp"
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(open = false)
            }
        }
        binaries.executable()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    sourceSets {
        wasmJsMain {
            kotlin.srcDir(generateServerConfig)
        }
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
        }
    }
}
