package com.mertbek.sharescreen.desktop

import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.util.Log
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Registers the installed app for sharescreen:// links, so invites opened in a browser can start it.
 * macOS reads the scheme from the Info.plist of the package instead.
 */
object LinkScheme {
    private const val TAG = "LinkScheme"
    private const val SCHEME = ConnectLink.SCHEME
    private const val LINUX_ENTRY = "sharescreen-link.desktop"

    fun register() {
        val app = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return
        val os = System.getProperty("os.name").orEmpty().lowercase()
        try {
            when {
                os.startsWith("windows") -> registerOnWindows(app)
                os.startsWith("linux") -> registerOnLinux(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register $SCHEME links", e)
        }
    }

    private fun registerOnWindows(app: String) {
        val root = WinReg.HKEY_CURRENT_USER
        val key = "Software\\Classes\\$SCHEME"
        val command = "$key\\shell\\open\\command"
        Advapi32Util.registryCreateKey(root, command)
        Advapi32Util.registrySetStringValue(root, key, "", "URL:ShareScreen")
        Advapi32Util.registrySetStringValue(root, key, "URL Protocol", "")
        Advapi32Util.registrySetStringValue(root, command, "", "\"$app\" \"%1\"")
    }

    private fun registerOnLinux(app: String) {
        if (app.any { it in "\"`$\\%\n" }) return
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.local/share")
        val applications = Path(dataHome, "applications").createDirectories()
        applications.resolve(LINUX_ENTRY).writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=ShareScreen
            Exec="$app" %u
            NoDisplay=true
            MimeType=x-scheme-handler/$SCHEME;
            """.trimIndent() + "\n",
        )
        ProcessBuilder("xdg-mime", "default", LINUX_ENTRY, "x-scheme-handler/$SCHEME")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(10, TimeUnit.SECONDS)
    }
}
