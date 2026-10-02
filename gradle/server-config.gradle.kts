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

val configuredWebApp: String? = (
    providers.gradleProperty("sharescreen.web").orNull
        ?: providers.environmentVariable("SHARESCREEN_WEB").orNull
        ?: localProperties.getProperty("sharescreen.web")
        ?: "https://mertbek.github.io/ShareScreen/"
    ).trim().takeIf { it.isNotEmpty() }

val configuredVersion: String = providers.gradleProperty("sharescreen.version").orNull ?: "dev"

fun kotlinString(value: String?): String =
    value?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"

extra["sharescreenServer"] = configuredServer
extra["sharescreenWebApp"] = configuredWebApp

val generateServerConfig = tasks.register("generateServerConfig") {
    val outputDir = layout.buildDirectory.dir("generated/serverConfig")
    val server = kotlinString(configuredServer)
    val webApp = kotlinString(configuredWebApp)
    val version = configuredVersion
    inputs.property("server", server)
    inputs.property("webApp", webApp)
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/mertbek/sharescreen/config/ServerConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.mertbek.sharescreen.config\n\n" +
                "object ServerConfig {\n" +
                "    val defaultServer: String? = $server\n" +
                "    val webApp: String? = $webApp\n" +
                "    const val versionName: String = \"$version\"\n" +
                "}\n",
        )
    }
}

extra["generateServerConfig"] = generateServerConfig
