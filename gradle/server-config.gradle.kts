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

val configuredVersion: String = providers.gradleProperty("sharescreen.version").orNull ?: "dev"

extra["sharescreenServer"] = configuredServer

val generateServerConfig = tasks.register("generateServerConfig") {
    val outputDir = layout.buildDirectory.dir("generated/serverConfig")
    val server = configuredServer
    val version = configuredVersion
    inputs.property("server", server.orEmpty())
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/mertbek/sharescreen/config/ServerConfig.kt").asFile
        file.parentFile.mkdirs()
        val literal = server?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"
        file.writeText(
            "package com.mertbek.sharescreen.config\n\n" +
                "object ServerConfig {\n" +
                "    val defaultServer: String? = $literal\n" +
                "    const val versionName: String = \"$version\"\n" +
                "}\n",
        )
    }
}

extra["generateServerConfig"] = generateServerConfig
