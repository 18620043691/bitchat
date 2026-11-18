package com.bitchat.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class MeshForegroundService : Service() {
    interface Listener {
        fun onStatus(status: String)
        fun onPeersChanged(peers: List<MeshPeer>)
        fun onMessage(message: MeshChatMessage)
        fun onPrivateMessage(message: MeshChatMessage)
        fun onGroupMessage(message: MeshChatMessage)
        fun onLinkCountChanged(count: Int)
    }

    inner class LocalBinder : Binder() {
        fun service(): MeshForegroundService = this@MeshForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private val listeners = linkedSetOf<Listener>()
    private val noiseLock = Any()
    private val noiseSessions = mutableMapOf<String, NoiseXXSession>()
    private val pendingNoisePayloads = mutableMapOf<String, MutableList<ByteArray>>()
    private val syncedGroupEpochs = mutableMapOf<String, MutableMap<String, Long>>()
    private var engine: MeshProtocolEngine? = null
    private var transport: BleMeshTransport? = null
    private var meshIdentity: MeshIdentity? = null
    private var groupStore: MeshGroupStore? = null
    @Volatile private var appVisible = false
    private var activePeers: List<MeshPeer> = emptyList()
    private var linkCount = 0
    private var statusText = "Starting nearby mesh"

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (engine == null) initializeMesh()
        return START_STICKY
    }

    override fun onDestroy() {
        transport?.stop()
        transport = null
        engine = null
        mediaExecutor.shutdown()
        super.onDestroy()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStatus(statusText)
        listener.onPeersChanged(activePeers)
        listener.onLinkCountChanged(linkCount)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun sendText(text: String) {
        val activeTransport = transport ?: error("Bluetooth mesh is not ready.")
        activeTransport.sendText(text)
    }

    fun sendPrivateText(peerId: String, text: String) {
        require(text.isNotBlank()) { "Private message cannot be empty." }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= 255) { "Private messages are limited to 255 UTF-8 bytes." }
        val payload = PrivateMessageCodec.encode(UUID.randomUUID().toString(), text)
            ?: throw IllegalArgumentException("Private message is not valid UTF-8 or is too long.")
        sendNoisePayload(peerId, byteArrayOf(PRIVATE_MESSAGE_TYPE) + payload)
        publishPrivateMessage(
            MeshChatMessage(
                senderId = engine?.localPeerId() ?: "local",
                nickname = engine?.nickname() ?: "You",
                text = text,
                timestamp = System.currentTimeMillis(),
                isLocal = true,
                conversationId = peerId,
                isPrivate = true
            )
        )
    }

    internal fun groups(): List<MeshGroup> = groupStore?.all().orEmpty()

    fun localFingerprint(): String = meshIdentity?.noiseFingerprint.orEmpty()

    internal fun createGroup(name: String): MeshGroup {
        val store = groupStore ?: error("Bluetooth mesh identity is not ready.")
        return store.create(name, engine?.nickname() ?: "Android")
            ?: throw IllegalArgumentException("Group name is invalid or could not be saved.")
    }

    fun sendGroupText(groupId: ByteArray, text: String) {
        val identity = meshIdentity ?: error("Bluetooth mesh identity is not ready.")
        val group = groupStore?.find(groupId) ?: error("This group is no longer available.")
        require(group.members.any { it.fingerprint == identity.noiseFingerprint }) {
            "You are not a member of this group."
        }
        val messageId = UUID.randomUUID().toString()
        val payload = MeshGroupMessageCodec.seal(
            group,
            text,
            identity,
            engine?.nickname() ?: "Android",
            messageId = messageId
        )
        val activeTransport = transport ?: error("Bluetooth mesh is not ready.")
        val packet = MeshPacket(
            type = MeshMessageType.GROUP_MESSAGE,
            senderId = identity.peerId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            ttl = PRIVATE_PACKET_TTL
        )
        if (!activeTransport.sendGroupPacket(packet)) {
            throw IllegalStateException("The Bluetooth send queues are full; group message was not sent.")
        }
        publishGroupMessage(
            MeshChatMessage(
                senderId = identity.peerIdHex,
                nickname = engine?.nickname() ?: "You",
                text = text,
                timestamp = packet.timestamp,
                isLocal = true,
                conversationId = group.conversationId,
                isGroup = true,
                messageId = messageId
            )
        )
    }

    fun inviteGroupMember(groupId: ByteArray, peerId: String) {
        val identity = meshIdentity ?: error("Bluetooth mesh identity is not ready.")
        val store = groupStore ?: error("Group store is unavailable.")
        val engine = engine ?: error("Bluetooth mesh is not ready.")
        val group = store.find(groupId) ?: error("This group is no longer available.")
        require(group.creatorFingerprint == identity.noiseFingerprint) { "Only the group creator can invite members." }
        require(engine.peers().any { it.id == peerId && it.connected }) {
            "The member must be connected directly over Bluetooth to be invited."
        }
        val peerIdentity = engine.cryptoIdentity(peerId) ?: error("The peer identity has not been verified yet.")
        val fingerprint = sha256(peerIdentity.first).hex()
        require(group.members.none { it.fingerprint == fingerprint }) { "This peer is already in the group." }
        require(group.members.size < MAX_GROUP_MEMBERS) { "The group is full." }

        val newMember = MeshGroupMember(fingerprint, peerIdentity.second, engine.peers().first { it.id == peerId }.nickname)
        val updated = store.rotate(groupId, group.members + newMember)
            ?: error("Unable to rotate the group key.")
        val state = MeshGroupState.create(updated, identity)?.encode()
            ?: error("Unable to sign the group invitation.")
        sendNoisePayload(peerId, byteArrayOf(GROUP_INVITE_TYPE) + state)
        markGroupStateSent(peerId, updated)
        syncGroupStates(engine.peers())
    }

    fun removeGroupMember(groupId: ByteArray, fingerprint: String) {
        val identity = meshIdentity ?: error("Bluetooth mesh identity is not ready.")
        val store = groupStore ?: error("Group store is unavailable.")
        val engine = engine ?: error("Bluetooth mesh is not ready.")
        val group = store.find(groupId) ?: error("This group is no longer available.")
        require(group.creatorFingerprint == identity.noiseFingerprint) { "Only the group creator can remove members." }
        val removed = group.members.firstOrNull { it.fingerprint == fingerprint }
            ?: error("That member is not in this group.")
        require(removed.fingerprint != identity.noiseFingerprint) { "The group creator cannot remove themselves." }
        val remaining = group.members.filterNot { it.fingerprint == fingerprint }
        val rotated = store.rotate(groupId, remaining) ?: error("Unable to rotate the group key.")
        syncGroupStates(engine.peers())
        val removedPeer = engine.peers().firstOrNull {
            it.connected &&
                engine.cryptoIdentity(it.id)?.first?.let { key -> sha256(key).hex() == fingerprint } == true
        }
        removedPeer?.let {
            val removalNotice = MeshGroupState.create(rotated.copy(key = ByteArray(GROUP_KEY_BYTES)), identity)?.encode()
                ?: error("Unable to sign the removal notice.")
            sendNoisePayload(it.id, byteArrayOf(GROUP_KEY_UPDATE_TYPE) + removalNotice)
        }
    }

    fun leaveGroup(groupId: ByteArray) {
        groupStore?.remove(groupId)
    }

    private fun sendNoisePayload(peerId: String, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_NOISE_PAYLOAD_BYTES) {
            "Encrypted control message is too large."
        }
        val activeTransport = transport ?: error("Bluetooth mesh is not ready.")
        val packetToSend = synchronized(noiseLock) {
            val identity = meshIdentity ?: error("Bluetooth mesh identity is not ready.")
            val recipient = peerId.toPeerIdBytes()
            val session = noiseSessions[peerId]
            if (session?.isEstablished == true) {
                securePacket(
                    MeshMessageType.NOISE_ENCRYPTED,
                    identity,
                    recipient,
                    session.encrypt(payload)
                )
            } else {
                val queued = pendingNoisePayloads.getOrPut(peerId) { mutableListOf() }
                require(queued.size < MAX_PENDING_PRIVATE_MESSAGES) {
                    "Private handshake is pending; wait before sending more messages."
                }
                queued += payload
                if (session == null) {
                    val initiator = NoiseXXSession(identity.noisePrivate, NoiseXXSession.Role.INITIATOR)
                    noiseSessions[peerId] = initiator
                    securePacket(
                        MeshMessageType.NOISE_HANDSHAKE,
                        identity,
                        recipient,
                        initiator.startHandshake()
                    )
                } else {
                    null
                }
            }
        }

        if (packetToSend != null && !activeTransport.sendSecurePacket(packetToSend)) {
            synchronized(noiseLock) {
                pendingNoisePayloads[peerId]?.let { queue ->
                    queue.removeLastOrNull()
                    if (queue.isEmpty()) pendingNoisePayloads.remove(peerId)
                }
                if (packetToSend.type == MeshMessageType.NOISE_HANDSHAKE) noiseSessions.remove(peerId)
            }
            throw IllegalStateException("The Bluetooth send queues are full; private message was not sent.")
        }
    }

    private fun syncGroupStates(peers: List<MeshPeer>) {
        val identity = meshIdentity ?: return
        val engine = engine ?: return
        val store = groupStore ?: return
        val connectedPeerIds = peers.filter { it.connected }.mapTo(mutableSetOf()) { it.id }
        syncedGroupEpochs.keys.retainAll(connectedPeerIds)
        for (peer in peers.filter { it.connected }) {
            val peerIdentity = engine.cryptoIdentity(peer.id) ?: continue
            val fingerprint = sha256(peerIdentity.first).hex()
            for (group in store.all().filter { group ->
                group.creatorFingerprint == identity.noiseFingerprint &&
                    group.members.any { it.fingerprint == fingerprint }
            }) {
                val groupKey = group.groupId.hex()
                if (syncedGroupEpochs[peer.id]?.get(groupKey) == group.epoch) continue
                try {
                    val state = MeshGroupState.create(group, identity)?.encode()
                        ?: error("Unable to sign the group update.")
                    sendNoisePayload(peer.id, byteArrayOf(GROUP_KEY_UPDATE_TYPE) + state)
                    markGroupStateSent(peer.id, group)
                } catch (error: Exception) {
                    updateStatus("Could not sync group ${group.name}: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun markGroupStateSent(peerId: String, group: MeshGroup) {
        syncedGroupEpochs.getOrPut(peerId) { mutableMapOf() }[group.groupId.hex()] = group.epoch
    }

    fun sendImage(fileName: String, mimeType: String, content: ByteArray) {
        val activeTransport = transport ?: error("Bluetooth mesh is not ready.")
        val extension = imageExtension(mimeType) ?: throw IllegalArgumentException("Choose a supported image.")
        require(content.size <= MeshFilePacket.MAX_IMAGE_BYTES && MeshFilePacket.isImage(mimeType, content)) {
            "Image must be a supported format and no larger than 512 KB."
        }
        val imageFile = File(File(filesDir, "sent-images").apply { mkdirs() }, "${UUID.randomUUID()}.$extension")
        imageFile.writeBytes(content)
        try {
            activeTransport.sendImage(fileName, mimeType, content, imageFile.absolutePath)
        } catch (error: Exception) {
            imageFile.delete()
            throw error
        }
    }

    fun localNickname(): String = engine?.nickname() ?: "Android"

    fun changeNickname(nickname: String) {
        IdentityStore(this).saveNickname(nickname)
        transport?.changeNickname(nickname)
    }

    private fun initializeMesh() {
        try {
            val store = IdentityStore(this)
            val identity = store.loadOrCreate()
            meshIdentity = identity
            groupStore = MeshGroupStore(store, identity)
            val mesh = MeshProtocolEngine(
                identity = identity,
                onMessage = { message ->
                    mainHandler.post { listeners.toList().forEach { it.onMessage(message) } }
                },
                onPeersChanged = { peers ->
                    mainHandler.post {
                        activePeers = peers
                        syncGroupStates(peers)
                        listeners.toList().forEach { it.onPeersChanged(peers) }
                    }
                },
                onRelay = { relay ->
                    mainHandler.postDelayed({ transport?.relay(relay) }, relay.delayMs)
                },
                peerAddresses = { peerId -> transport?.peerAddresses(peerId) ?: emptySet() },
                initialSigningKeys = store.loadPeerPins(),
                onPeerPinned = store::savePeerPin,
                onImage = ::handleImage,
                onNoisePacket = ::handleNoisePacket,
                onGroupPacket = ::handleGroupPacket
            )
            mesh.setNickname(store.nickname(identity))
            engine = mesh
            transport = BleMeshTransport(
                this,
                mesh,
                object : BleMeshTransport.Listener {
                    override fun onStatus(status: String) = updateStatus(status)
                    override fun onLinkCountChanged(count: Int) {
                        mainHandler.post {
                            linkCount = count
                            listeners.toList().forEach { it.onLinkCountChanged(count) }
                        }
                    }

                    override fun onPacket(packet: MeshPacket, address: String) {
                        try {
                            mesh.receive(packet, address)
                        } catch (error: Exception) {
                            updateStatus("Could not securely accept a nearby peer: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            ).also { it.start() }
            updateStatus("Nearby shared mesh chat is active")
            transport?.announce()
        } catch (error: Exception) {
            updateStatus("Could not start Bluetooth mesh: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun updateStatus(status: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateStatus(status) }
            return
        }
        statusText = status
        listeners.toList().forEach { it.onStatus(status) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun handleImage(image: MeshImageTransfer) {
        if (image.localPath != null) {
            val message = image.message
            mainHandler.post { listeners.toList().forEach { it.onMessage(message) } }
            return
        }
        mediaExecutor.execute {
            try {
                val extension = imageExtension(image.mimeType) ?: return@execute
                val destination = File(File(filesDir, "received-images").apply { mkdirs() }, "${UUID.randomUUID()}.$extension")
                destination.writeBytes(image.content)
                val message = image.message.copy(imagePath = destination.absolutePath)
                mainHandler.post { listeners.toList().forEach { it.onMessage(message) } }
            } catch (_: Exception) {
                mainHandler.post { updateStatus("An incoming image could not be saved") }
            }
        }
    }

    private fun handleNoisePacket(packet: MeshPacket, expectedNoiseKey: ByteArray): Boolean {
        val senderId = packet.senderId.hex()
        val responsePackets = mutableListOf<MeshPacket>()
        val receivedMessages = mutableListOf<MeshChatMessage>()
        try {
            synchronized(noiseLock) {
                val identity = meshIdentity ?: return false
                var session = noiseSessions[senderId]
                if (packet.type == MeshMessageType.NOISE_HANDSHAKE) {
                    val isInitialMessage = packet.payload.size == NOISE_INITIAL_MESSAGE_SIZE
                    if (session?.isEstablished == true) return false
                    if (session == null) {
                        if (!isInitialMessage) return false
                        session = NoiseXXSession(identity.noisePrivate, NoiseXXSession.Role.RESPONDER)
                        noiseSessions[senderId] = session
                    } else if (isInitialMessage && session.role == NoiseXXSession.Role.INITIATOR) {
                        if (identity.peerIdHex < senderId) return true
                        session = NoiseXXSession(identity.noisePrivate, NoiseXXSession.Role.RESPONDER)
                        noiseSessions[senderId] = session
                    }

                    val activeSession = checkNotNull(session)
                    val response = activeSession.processHandshakeMessage(packet.payload)
                    val remoteKey = activeSession.remoteStaticKey
                    if (remoteKey != null && !remoteKey.contentEquals(expectedNoiseKey)) {
                        noiseSessions.remove(senderId)
                        pendingNoisePayloads.remove(senderId)
                        updateStatus("Private chat identity verification failed for ${senderId.take(8)}")
                        return false
                    }
                    if (response != null) {
                        responsePackets += securePacket(
                            MeshMessageType.NOISE_HANDSHAKE,
                            identity,
                            packet.senderId,
                            response
                        )
                    }
                    if (activeSession.isEstablished) {
                        val queued = pendingNoisePayloads.remove(senderId).orEmpty()
                        queued.forEach { cleartext ->
                            responsePackets += securePacket(
                                MeshMessageType.NOISE_ENCRYPTED,
                                identity,
                                packet.senderId,
                                activeSession.encrypt(cleartext)
                            )
                        }
                    }
                } else {
                    val activeSession = session?.takeIf { it.isEstablished } ?: return false
                    if (activeSession.remoteStaticKey?.contentEquals(expectedNoiseKey) != true) {
                        noiseSessions.remove(senderId)
                        pendingNoisePayloads.remove(senderId)
                        return false
                    }
                    val plaintext = activeSession.decrypt(packet.payload)
                    if (plaintext.isEmpty()) return false
                    when (plaintext[0]) {
                        PRIVATE_MESSAGE_TYPE -> {
                            val decoded = PrivateMessageCodec.decode(plaintext.copyOfRange(1, plaintext.size)) ?: return false
                            val nickname = engine?.peers()?.firstOrNull { it.id == senderId }?.nickname ?: senderId.take(8)
                            receivedMessages += MeshChatMessage(
                                senderId = senderId,
                                nickname = nickname,
                                text = decoded.second,
                                timestamp = packet.timestamp,
                                isLocal = false,
                                conversationId = senderId,
                                isPrivate = true,
                                messageId = decoded.first
                            )
                        }
                        GROUP_INVITE_TYPE, GROUP_KEY_UPDATE_TYPE ->
                            if (!applyGroupState(senderId, expectedNoiseKey, plaintext.copyOfRange(1, plaintext.size))) return false
                        else -> return true
                    }
                }
            }
        } catch (error: Exception) {
            updateStatus("Could not accept a private message: ${error.message ?: error.javaClass.simpleName}")
            return false
        }

        val activeTransport = transport
        responsePackets.forEach { response ->
            if (activeTransport?.sendSecurePacket(response) != true) {
                updateStatus("A private chat response could not be queued")
                return false
            }
        }
        receivedMessages.forEach(::publishPrivateMessage)
        return true
    }

    private fun publishPrivateMessage(message: MeshChatMessage) {
        if (!message.isLocal) notifyMessage(message, "Private message from ${message.nickname}")
        mainHandler.post { listeners.toList().forEach { it.onPrivateMessage(message) } }
    }

    private fun applyGroupState(senderId: String, senderNoiseKey: ByteArray, payload: ByteArray): Boolean {
        val state = MeshGroupState.decode(payload) ?: return false
        val fingerprint = sha256(senderNoiseKey).hex()
        if (state.creatorFingerprint != fingerprint) return false
        val creator = state.members.firstOrNull { it.fingerprint == fingerprint } ?: return false
        val announcedSigningKey = engine?.cryptoIdentity(senderId)?.second ?: return false
        if (!creator.signingKey.contentEquals(announcedSigningKey)) return false
        val store = groupStore ?: return false
        val result = store.apply(state)
        if (result == MeshGroupStore.ApplyResult.REJECTED) return false
        if (result == MeshGroupStore.ApplyResult.ADDED || result == MeshGroupStore.ApplyResult.REMOVED) {
            val text = if (result == MeshGroupStore.ApplyResult.ADDED) {
                "You were added to ${state.name}"
            } else {
                "You were removed from ${state.name}"
            }
            publishGroupMessage(
                MeshChatMessage(
                    senderId = senderId,
                    nickname = creator.nickname,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isLocal = false,
                    conversationId = "group_${state.groupId.hex()}",
                    isGroup = true
                )
            )
        }
        return true
    }

    private fun handleGroupPacket(packet: MeshPacket) {
        val identity = meshIdentity ?: return
        val groupId = MeshGroupMessageCodec.groupId(packet.payload) ?: return
        val group = groupStore?.find(groupId) ?: return
        val message = MeshGroupMessageCodec.open(packet.payload, group, identity.signingPublic) ?: return
        if (!message.isLocal) publishGroupMessage(message)
    }

    private fun publishGroupMessage(message: MeshChatMessage) {
        if (!message.isLocal) {
            val groupName = message.conversationId
                ?.removePrefix("group_")
                ?.hexToByteArray()
                ?.let { groupStore?.find(it)?.name }
                ?: "group"
            notifyMessage(message, "${message.nickname} · $groupName")
        }
        mainHandler.post { listeners.toList().forEach { it.onGroupMessage(message) } }
    }

    private fun notifyMessage(message: MeshChatMessage, title: String) {
        if (appVisible || (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return
        val conversationId = message.conversationId ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(message.text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle("BitChat")
                    .setContentText("New offline chat message")
                    .build()
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(
            conversationId,
            ((message.messageId ?: UUID.randomUUID().toString()).hashCode() and Int.MAX_VALUE),
            notification
        )
    }

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0 || any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun securePacket(
        type: Int,
        identity: MeshIdentity,
        recipientId: ByteArray,
        payload: ByteArray
    ) = MeshPacket(
        type = type,
        senderId = identity.peerId,
        timestamp = System.currentTimeMillis(),
        payload = payload,
        ttl = PRIVATE_PACKET_TTL,
        recipientId = recipientId
    )

    private fun String.toPeerIdBytes(): ByteArray {
        require(length == 16 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid private chat peer identifier."
        }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun imageExtension(mimeType: String): String? = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nearby mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bluetooth mesh chat and message relay"
        }
        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Chat messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Private and group messages received over Bluetooth"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, messageChannel))
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("bitchat · Nearby mesh")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val PRIVATE_MESSAGE_TYPE: Byte = 0x01
        private const val GROUP_INVITE_TYPE: Byte = 0x06
        private const val GROUP_KEY_UPDATE_TYPE: Byte = 0x07
        private const val PRIVATE_PACKET_TTL = 7
        private const val NOISE_INITIAL_MESSAGE_SIZE = 32
        private const val MAX_PENDING_PRIVATE_MESSAGES = 16
        private const val MAX_NOISE_PAYLOAD_BYTES = 16_384
        private const val MAX_GROUP_MEMBERS = 16
        private const val GROUP_KEY_BYTES = 32
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val CHANNEL_ID = "bitchat-mesh"
        private const val MESSAGE_CHANNEL_ID = "bitchat-messages"
        private const val NOTIFICATION_ID = 7301
    }
}
