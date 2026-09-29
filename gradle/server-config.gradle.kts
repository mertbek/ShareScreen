import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

val configuredServer: String? = (
    providers.gradleProperty("sharescreen.server").orNull
        ?: providers.environmentVariable("SHARESCREEN_SERVER").orNull
        ?: localProperties.getProperty("sharescreen.server")
    )?.trim()?.takeIf { it.isNotEmpty() }

extra["sharescreenServer"] = configuredServer

val generateServerConfig = tasks.register("generateServerConfig") {
    val outputDir = layout.buildDirectory.dir("generated/serverConfig")
    val server = configuredServer
    inputs.property("server", server.orEmpty())
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/mertbek/sharescreen/config/ServerConfig.kt").asFile
        file.parentFile.mkdirs()
        val literal = server?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"
        file.writeText(
            "package com.mertbek.sharescreen.config\n\nobject ServerConfig {\n    val defaultServer: String? = $literal\n}\n",
        )
    }
}

extra["generateServerConfig"] = generateServerConfig
