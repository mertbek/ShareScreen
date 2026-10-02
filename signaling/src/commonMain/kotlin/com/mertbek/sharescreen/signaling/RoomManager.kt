package com.mertbek.sharescreen.signaling

import com.mertbek.sharescreen.signaling.SignalMessage.Error
import com.mertbek.sharescreen.signaling.SignalMessage.Hello
import com.mertbek.sharescreen.signaling.SignalMessage.JoinDecision
import com.mertbek.sharescreen.signaling.SignalMessage.JoinRequest
import com.mertbek.sharescreen.signaling.SignalMessage.Kick
import com.mertbek.sharescreen.signaling.SignalMessage.Leave
import com.mertbek.sharescreen.signaling.SignalMessage.PeerLeft
import com.mertbek.sharescreen.signaling.SignalMessage.SessionEnded
import com.mertbek.sharescreen.signaling.SignalMessage.Welcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface PeerLink {
    fun send(message: SignalMessage)

    fun close()
}

data class SignalingConfig(
    val singleRoom: Boolean = false,
    val hostSecret: String? = null,
    val maxWrongPinsPerMinute: Int = 10,
    val maxViewersPerRoom: Int = 4,
    val maxWaitingPerRoom: Int = 8,
    val iceServers: () -> List<IceServerConfig> = { emptyList() },
    val resumeGraceMillis: Long = 30_000,
)

class Member internal constructor(
    val id: String,
    val role: PeerRole,
    val deviceName: String,
    val roomCode: String,
    internal val resumeToken: String,
    link: PeerLink,
    internal val address: String? = null,
    internal val hasPass: Boolean = false,
) {
    internal var approved: Boolean = role == PeerRole.HOST

    internal var link: PeerLink? = link
        private set
    private var detachedAt = 0L
    private val missed = ArrayDeque<SignalMessage>()

    internal fun send(message: SignalMessage) {
        val link = link
        if (link != null) {
            link.send(message)
        } else {
            if (missed.size == MAX_MISSED_MESSAGES) missed.removeFirst()
            missed.addLast(message)
        }
    }

    internal fun detach(now: Long) {
        link = null
        detachedAt = now
    }

    internal fun attach(link: PeerLink) {
        this.link?.close()
        this.link = link
    }

    internal fun deliverMissed() {
        val link = link ?: return
        while (missed.isNotEmpty()) link.send(missed.removeFirst())
    }

    internal fun isExpired(now: Long, graceMillis: Long): Boolean = link == null && now - detachedAt >= graceMillis

    internal fun close() {
        link?.close()
    }

    private companion object {
        const val MAX_MISSED_MESSAGES = 100
    }
}

private class Room(val code: String, val pin: String?, val host: Member) {
    val wrongPinTimes = ArrayDeque<Long>()

    /** When each address last gave a wrong PIN; null stands for connections whose address is unknown. */
    val wrongPinAddresses = HashMap<String?, Long>()

    /** When the host last turned down a request from each address, in a room without a PIN. */
    val refusedAddresses = HashMap<String, Long>()

    val viewers = LinkedHashMap<String, Member>()

    fun member(id: String): Member? = if (host.id == id) host else viewers[id]

    fun watching(): Int = viewers.values.count { it.approved }

    fun waiting(): List<Member> = viewers.values.filterNot { it.approved }
}

class RoomManager(
    private val config: SignalingConfig,
    private val newPeerId: () -> String = ::randomPeerId,
    private val newRoomCode: () -> String = ::randomRoomCode,
    private val clockMillis: () -> Long = ::currentTimeMillis,
    private val newResumeToken: () -> String = ::randomResumeToken,
) {
    private val mutex = Mutex()
    private val rooms = HashMap<String, Room>()
    private val membersByToken = HashMap<String, Member>()

    suspend fun join(link: PeerLink, hello: Hello, address: String? = null): Member? = mutex.withLock {
        expireDetached()
        if (hello.protocolVersion != PROTOCOL_VERSION) return@withLock refuse(link, ErrorCode.UNSUPPORTED_VERSION)
        if (hello.resumeToken != null) return@withLock resume(link, hello.role, hello.resumeToken)
        val deviceName = hello.deviceName.trim().take(MAX_DEVICE_NAME_LENGTH)
        when (hello.role) {
            PeerRole.HOST -> joinAsHost(link, hello, deviceName)
            PeerRole.VIEWER -> joinAsViewer(link, hello, deviceName, address)
        }
    }

    suspend fun handle(sender: Member, message: SignalMessage): Unit = mutex.withLock {
        val room = rooms[sender.roomCode]?.takeIf { it.member(sender.id) === sender } ?: return@withLock
        when {
            message is Leave -> remove(room, sender)
            message is JoinDecision && sender.role == PeerRole.HOST -> decide(room, message)
            message is Kick && sender.role == PeerRole.HOST -> kick(room, message.viewerId)
            message is Routed -> relay(room, sender, message)
            else -> sender.send(Error(ErrorCode.PROTOCOL, "Unexpected message"))
        }
    }

    suspend fun disconnected(member: Member, link: PeerLink): Unit = mutex.withLock {
        rooms[member.roomCode]?.takeIf { it.member(member.id) === member } ?: return@withLock
        if (member.link === link) member.detach(clockMillis())
    }

    suspend fun expire(): Unit = mutex.withLock { expireDetached() }

    suspend fun expirePeriodically(interval: Duration = EXPIRY_INTERVAL): Nothing {
        while (true) {
            delay(interval)
            expire()
        }
    }

    private fun joinAsHost(link: PeerLink, hello: Hello, deviceName: String): Member? {
        if (config.hostSecret != null && hello.hostSecret != config.hostSecret) {
            return refuse(link, ErrorCode.UNAUTHORIZED)
        }
        val code = if (config.singleRoom) SINGLE_ROOM_CODE else uniqueRoomCode()
        if (code in rooms) return refuse(link, ErrorCode.HOST_EXISTS)

        val host = newMember(PeerRole.HOST, deviceName, code, link)
        rooms[code] = Room(code, hello.pin, host)
        link.send(welcome(host, hostId = host.id))
        return host
    }

    private fun joinAsViewer(link: PeerLink, hello: Hello, deviceName: String, address: String?): Member? {
        val code = if (config.singleRoom) SINGLE_ROOM_CODE else hello.roomCode?.trim()?.uppercase()
        val room = code?.let(rooms::get) ?: return refuse(link, ErrorCode.ROOM_NOT_FOUND)
        val now = clockMillis()
        // The host checks a pass and quietly turns away one it does not know, so a pass stands in for the PIN.
        val pass = hello.pass
        if (pass == null) {
            room.wrongPinTimes.removeAll { now - it >= PIN_WINDOW_MILLIS }
            room.wrongPinAddresses.values.removeAll { now - it >= PIN_WINDOW_MILLIS }
            // Only addresses that guessed wrong wait out the lock, so someone guessing cannot keep the others out.
            val guessed = address in room.wrongPinAddresses || room.wrongPinAddresses.size >= MAX_GUESSING_ADDRESSES
            if (room.wrongPinTimes.size >= config.maxWrongPinsPerMinute && guessed) {
                return refuse(link, ErrorCode.TOO_MANY_ATTEMPTS)
            }
            if (room.pin != null && room.pin != hello.pin) {
                room.wrongPinTimes.addLast(now)
                room.wrongPinAddresses[address] = now
                return refuse(link, ErrorCode.INVALID_PIN)
            }
        }
        if (room.pin == null && address != null) {
            room.refusedAddresses.values.removeAll { now - it >= REFUSED_WAIT_MILLIS }
            if (pass == null && address in room.refusedAddresses) return refuse(link, ErrorCode.REJECTED)
            // Without a PIN anyone nearby can ask, so each device has one request at a time and a new one replaces it.
            room.waiting().firstOrNull { it.address == address }?.let { withdraw(room, it) }
        }
        if (room.watching() >= config.maxViewersPerRoom || room.waiting().size >= config.maxWaitingPerRoom) {
            return refuse(link, ErrorCode.ROOM_FULL)
        }

        val viewer = newMember(PeerRole.VIEWER, deviceName, room.code, link, address, hasPass = pass != null)
        room.viewers[viewer.id] = viewer
        link.send(welcome(viewer, hostId = room.host.id))
        room.host.send(JoinRequest(viewerId = viewer.id, deviceName = deviceName, pass = pass))
        return viewer
    }

    private fun resume(link: PeerLink, role: PeerRole, token: String): Member? {
        val member = membersByToken[token]?.takeIf { it.role == role } ?: return refuse(link, ErrorCode.RESUME_FAILED)
        val room = rooms.getValue(member.roomCode)
        member.attach(link)
        link.send(welcome(member, hostId = room.host.id, resumed = true))
        member.deliverMissed()
        return member
    }

    private fun decide(room: Room, decision: JoinDecision) {
        val viewer = room.viewers[decision.viewerId]?.takeUnless { it.approved } ?: return
        if (decision.accepted) {
            viewer.approved = true
            viewer.send(decision)
            // Requests wait only while there is room for them.
            if (room.watching() >= config.maxViewersPerRoom) room.waiting().forEach { turnAway(room, it) }
        } else {
            forget(room, viewer)
            // A pass the host does not know is no reason to keep the device from asking the usual way.
            if (room.pin == null && viewer.address != null && !viewer.hasPass) room.refusedAddresses[viewer.address] = clockMillis()
            viewer.send(Error(ErrorCode.REJECTED))
            viewer.close()
        }
    }

    private fun withdraw(room: Room, viewer: Member) {
        forget(room, viewer)
        viewer.close()
        room.host.send(PeerLeft(viewer.id))
    }

    private fun turnAway(room: Room, viewer: Member) {
        forget(room, viewer)
        viewer.send(Error(ErrorCode.ROOM_FULL))
        viewer.close()
        room.host.send(PeerLeft(viewer.id))
    }

    private fun kick(room: Room, viewerId: String) {
        val viewer = room.viewers[viewerId] ?: return
        forget(room, viewer)
        viewer.send(Error(ErrorCode.KICKED))
        viewer.close()
        room.host.send(PeerLeft(viewer.id))
    }

    private fun remove(room: Room, member: Member) {
        forget(room, member)
        if (member.role == PeerRole.HOST) {
            room.viewers.values.toList().forEach {
                forget(room, it)
                it.send(SessionEnded)
                it.close()
            }
        } else {
            room.host.send(PeerLeft(member.id))
        }
    }

    private fun forget(room: Room, member: Member) {
        membersByToken.remove(member.resumeToken)
        if (member.role == PeerRole.HOST) rooms.remove(room.code) else room.viewers.remove(member.id)
    }

    private fun expireDetached() {
        val now = clockMillis()
        for (room in rooms.values.toList()) {
            if (room.host.isExpired(now, config.resumeGraceMillis)) {
                remove(room, room.host)
            } else {
                room.viewers.values.filter { it.isExpired(now, config.resumeGraceMillis) }.forEach { remove(room, it) }
            }
        }
    }

    private fun relay(room: Room, sender: Member, message: Routed) {
        val target = room.member(message.to)
        val allowed = when {
            target == null -> false
            sender.role == PeerRole.HOST -> target.role == PeerRole.VIEWER && target.approved
            else -> sender.approved && target.role == PeerRole.HOST
        }
        if (target == null || !allowed) {
            sender.send(Error(ErrorCode.PROTOCOL, "Cannot send to ${message.to}"))
            return
        }
        target.send(message.withFrom(sender.id))
    }

    private fun newMember(
        role: PeerRole,
        deviceName: String,
        roomCode: String,
        link: PeerLink,
        address: String? = null,
        hasPass: Boolean = false,
    ): Member = Member(newPeerId(), role, deviceName, roomCode, newResumeToken(), link, address, hasPass)
        .also { membersByToken[it.resumeToken] = it }

    private fun welcome(member: Member, hostId: String, resumed: Boolean = false) = Welcome(
        peerId = member.id,
        roomCode = member.roomCode,
        hostId = hostId,
        iceServers = config.iceServers(),
        resumeToken = member.resumeToken,
        resumed = resumed,
    )

    private fun uniqueRoomCode(): String {
        var code: String
        do {
            code = newRoomCode()
        } while (code in rooms)
        return code
    }

    private fun refuse(link: PeerLink, code: ErrorCode): Member? {
        link.send(Error(code))
        link.close()
        return null
    }

    companion object {
        const val SINGLE_ROOM_CODE = "LAN"
        private const val MAX_DEVICE_NAME_LENGTH = 40
        private const val PIN_WINDOW_MILLIS = 60_000L
        private const val MAX_GUESSING_ADDRESSES = 256
        private const val REFUSED_WAIT_MILLIS = 60_000L
        private val EXPIRY_INTERVAL = 5.seconds
    }
}

private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

fun randomRoomCode(length: Int = 6): String {
    val bytes = secureRandomBytes(length)
    return CharArray(length) { ROOM_CODE_ALPHABET[bytes[it].toInt() and (ROOM_CODE_ALPHABET.length - 1)] }.concatToString()
}

private val resumeTokenEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

private fun randomResumeToken(): String = resumeTokenEncoder.encode(secureRandomBytes(16))

@OptIn(ExperimentalUuidApi::class)
private fun randomPeerId(): String = Uuid.random().toString()

private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
