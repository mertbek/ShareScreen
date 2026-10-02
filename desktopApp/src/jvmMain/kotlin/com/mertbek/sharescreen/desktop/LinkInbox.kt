package com.mertbek.sharescreen.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Passes the links that later launches of the app are started with to the instance that is already
 * running. The first instance holds a lock in the directory and listens on a loopback port that it
 * writes next to the lock together with a token.
 */
class LinkInbox private constructor(
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
) : AutoCloseable {

    fun listen(onLink: (String) -> Unit) {
        thread(isDaemon = true, name = "LinkInbox") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use { receive(it)?.let(onLink) }
            }
        }
    }

    private fun receive(socket: Socket): String? = try {
        socket.soTimeout = READ_TIMEOUT_MILLIS
        val bytes = socket.getInputStream().readNBytes(MAX_MESSAGE_BYTES + 1)
        val message = bytes.decodeToString().trimEnd('\n')
        val prefix = "$token "
        if (bytes.size > MAX_MESSAGE_BYTES || !message.startsWith(prefix)) null
        else message.substring(prefix.length).takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { lock.channel().close() }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val PORT_FILE = "instance.port"
        private const val READ_TIMEOUT_MILLIS = 2_000
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val MAX_MESSAGE_BYTES = 8 * 1024
        private const val FORWARD_ATTEMPTS = 5
        private const val FORWARD_RETRY_MILLIS = 200L

        /** Returns the inbox when this is the first instance, or null when another instance has it. */
        fun open(directory: Path): LinkInbox? {
            val channel = try {
                createPrivateDirectory(directory)
                FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (_: IOException) {
                return null
            }
            return try {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    null
                } else {
                    val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
                    val token = newToken()
                    writePrivate(directory.resolve(PORT_FILE), "${server.localPort} $token")
                    LinkInbox(lock, server, token)
                }
            } catch (_: IOException) {
                runCatching { channel.close() }
                null
            }
        }

        /** Sends the link to the instance that has the inbox and returns whether it arrived. */
        fun forward(directory: Path, link: String): Boolean {
            repeat(FORWARD_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(FORWARD_RETRY_MILLIS)
                try {
                    val (port, token) = directory.resolve(PORT_FILE).readText().trim().split(' ', limit = 2)
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), CONNECT_TIMEOUT_MILLIS)
                        socket.getOutputStream().write("$token $link\n".encodeToByteArray())
                        socket.shutdownOutput()
                    }
                    return true
                } catch (_: IOException) {
                } catch (_: RuntimeException) {
                }
            }
            return false
        }

        private fun newToken(): String {
            val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private val isPosix get() = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

        private fun createPrivateDirectory(directory: Path) {
            directory.createDirectories()
            if (isPosix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }

        private fun writePrivate(file: Path, text: String) {
            if (isPosix && Files.notExists(file)) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            file.writeText(text)
        }
    }
}
