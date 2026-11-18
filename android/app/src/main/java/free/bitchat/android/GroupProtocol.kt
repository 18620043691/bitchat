package com.bitchat.droid

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal data class MeshGroupMember(
    val fingerprint: String,
    val signingKey: ByteArray,
    val nickname: String
)

internal data class MeshGroup(
    val groupId: ByteArray,
    val name: String,
    val key: ByteArray,
    val epoch: Long,
    val members: List<MeshGroupMember>,
    val creatorFingerprint: String,
    val signature: ByteArray = ByteArray(0)
) {
    val conversationId: String get() = "group_${groupId.hex()}"
    fun memberForSigningKey(key: ByteArray): MeshGroupMember? =
        members.firstOrNull { it.signingKey.contentEquals(key) }
}

internal data class MeshGroupState(
    val groupId: ByteArray,
    val name: String,
    val key: ByteArray,
    val epoch: Long,
    val members: List<MeshGroupMember>,
    val creatorFingerprint: String,
    val signature: ByteArray
) {
    fun encode(): ByteArray? {
        val roster = encodeRoster(members) ?: return null
        val fingerprint = creatorFingerprint.hexToBytes() ?: return null
        if (groupId.size != GROUP_ID_BYTES || key.size != GROUP_KEY_BYTES || epoch !in 1..MAX_EPOCH ||
            fingerprint.size != FINGERPRINT_BYTES || signature.size != SIGNATURE_BYTES
        ) return null
        return encodeTlvs(
            listOf(
                0x01 to groupId,
                0x02 to name.toByteArray(Charsets.UTF_8),
                0x03 to key,
                0x04 to epoch.toUInt32Bytes(),
                0x05 to roster,
                0x06 to fingerprint,
                0x07 to signature
            )
        )
    }

    fun verify(): Boolean {
        if (members.size !in 1..MAX_GROUP_MEMBERS || key.size != GROUP_KEY_BYTES ||
            groupId.size != GROUP_ID_BYTES || epoch !in 1..MAX_EPOCH
        ) return false
        val creator = members.firstOrNull { it.fingerprint == creatorFingerprint } ?: return false
        val roster = encodeRoster(members) ?: return false
        val signed = signingContent(groupId, epoch, key, roster, name)
        return MessageIdentityVerifier.verify(signed, signature, creator.signingKey)
    }

    fun asGroup() = MeshGroup(
        groupId.copyOf(),
        name,
        key.copyOf(),
        epoch,
        members,
        creatorFingerprint,
        signature.copyOf()
    )

    companion object {
        private val DOMAIN = "bitchat-group-v1".toByteArray(Charsets.UTF_8)

        fun create(group: MeshGroup, identity: MeshIdentity): MeshGroupState? {
            if (group.creatorFingerprint != identity.noiseFingerprint) return null
            val roster = encodeRoster(group.members) ?: return null
            val fingerprint = group.creatorFingerprint.hexToBytes() ?: return null
            if (group.groupId.size != GROUP_ID_BYTES || group.key.size != GROUP_KEY_BYTES ||
                group.epoch !in 1..MAX_EPOCH || fingerprint.size != FINGERPRINT_BYTES
            ) return null
            val content = signingContent(group.groupId, group.epoch, group.key, roster, group.name)
            return MeshGroupState(
                group.groupId.copyOf(),
                group.name,
                group.key.copyOf(),
                group.epoch,
                group.members,
                group.creatorFingerprint,
                identity.sign(content)
            )
        }

        fun decode(data: ByteArray): MeshGroupState? {
            val fields = parseTlvs(data) ?: return null
            val groupId = fields[0x01]?.takeIf { it.size == GROUP_ID_BYTES } ?: return null
            val nameBytes = fields[0x02] ?: return null
            val name = nameBytes.toString(Charsets.UTF_8).takeIf {
                it.isNotBlank() && it.toByteArray(Charsets.UTF_8).contentEquals(nameBytes) && it.length <= 40
            } ?: return null
            val key = fields[0x03]?.takeIf { it.size == GROUP_KEY_BYTES } ?: return null
            val epoch = fields[0x04]?.toUInt32() ?: return null
            val members = decodeRoster(fields[0x05] ?: return null) ?: return null
            val creator = fields[0x06]?.takeIf { it.size == FINGERPRINT_BYTES }?.hex() ?: return null
            val signature = fields[0x07]?.takeIf { it.size == SIGNATURE_BYTES } ?: return null
            return MeshGroupState(groupId, name, key, epoch, members, creator, signature)
                .takeIf { it.verify() }
        }

        private fun signingContent(
            groupId: ByteArray,
            epoch: Long,
            key: ByteArray,
            roster: ByteArray,
            name: String
        ): ByteArray = DOMAIN + groupId + epoch.toUInt32Bytes() +
            sha256(key) + sha256(roster) + sha256(name.toByteArray(Charsets.UTF_8))
    }
}

internal object MeshGroupMessageCodec {
    private const val MESSAGE_DOMAIN = "bitchat-group-msg-v1"

    fun seal(
        group: MeshGroup,
        content: String,
        identity: MeshIdentity,
        nickname: String,
        messageId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): ByteArray {
        require(content.isNotEmpty() && content.toByteArray(Charsets.UTF_8).size <= MAX_GROUP_MESSAGE_BYTES)
        require(nickname.toByteArray(Charsets.UTF_8).size <= 64)
        require(group.key.size == GROUP_KEY_BYTES && group.groupId.size == GROUP_ID_BYTES)
        val signingContent = MESSAGE_DOMAIN.toByteArray(Charsets.UTF_8) +
            group.groupId + group.epoch.toUInt32Bytes() +
            messageId.toByteArray(Charsets.UTF_8) + timestamp.toUInt64Bytes() +
            content.toByteArray(Charsets.UTF_8)
        val signature = identity.sign(signingContent)
        val inner = encodeTlvs(
            listOf(
                0x01 to messageId.toByteArray(Charsets.UTF_8),
                0x02 to identity.signingPublic,
                0x03 to nickname.toByteArray(Charsets.UTF_8),
                0x04 to timestamp.toUInt64Bytes(),
                0x05 to content.toByteArray(Charsets.UTF_8),
                0x06 to signature
            )
        )
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val aad = group.groupId + group.epoch.toUInt32Bytes()
        val ciphertext = aead(true, group.key, nonce, aad, inner)
        return encodeTlvs(
            listOf(
                0x01 to group.groupId,
                0x02 to group.epoch.toUInt32Bytes(),
                0x03 to nonce,
                0x04 to ciphertext
            )
        )
    }

    fun open(payload: ByteArray, group: MeshGroup, localSigningKey: ByteArray): MeshChatMessage? {
        val envelope = parseTlvs(payload) ?: return null
        val groupId = envelope[0x01]?.takeIf { it.size == GROUP_ID_BYTES } ?: return null
        val epoch = envelope[0x02]?.toUInt32() ?: return null
        val nonce = envelope[0x03]?.takeIf { it.size == 12 } ?: return null
        val ciphertext = envelope[0x04]?.takeIf { it.size > 16 } ?: return null
        if (!group.groupId.contentEquals(groupId) || group.epoch != epoch) return null
        val plaintext = try {
            aead(false, group.key, nonce, groupId + epoch.toUInt32Bytes(), ciphertext)
        } catch (_: Exception) {
            return null
        }
        val fields = parseTlvs(plaintext) ?: return null
        val messageIdBytes = fields[0x01] ?: return null
        val messageId = messageIdBytes.toString(Charsets.UTF_8).takeIf {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).contentEquals(messageIdBytes)
        } ?: return null
        val senderKey = fields[0x02]?.takeIf { it.size == 32 } ?: return null
        val nicknameBytes = fields[0x03] ?: return null
        val nickname = nicknameBytes.toString(Charsets.UTF_8).takeIf {
            it.toByteArray(Charsets.UTF_8).contentEquals(nicknameBytes)
        } ?: return null
        val timestamp = fields[0x04]?.toUInt64() ?: return null
        val contentBytes = fields[0x05] ?: return null
        val content = contentBytes.toString(Charsets.UTF_8).takeIf {
            it.toByteArray(Charsets.UTF_8).contentEquals(contentBytes)
        } ?: return null
        val signature = fields[0x06]?.takeIf { it.size == SIGNATURE_BYTES } ?: return null
        val signingContent = MESSAGE_DOMAIN.toByteArray(Charsets.UTF_8) +
            groupId + epoch.toUInt32Bytes() +
            messageId.toByteArray(Charsets.UTF_8) + timestamp.toUInt64Bytes() + contentBytes
        val member = group.memberForSigningKey(senderKey) ?: return null
        if (!MessageIdentityVerifier.verify(signingContent, signature, senderKey)) return null
        return MeshChatMessage(
            senderId = member.fingerprint.take(16),
            nickname = member.nickname.ifEmpty { nickname },
            text = content,
            timestamp = timestamp.coerceAtMost(System.currentTimeMillis()),
            isLocal = senderKey.contentEquals(localSigningKey),
            conversationId = group.conversationId,
            isGroup = true,
            messageId = messageId
        )
    }

    fun groupId(payload: ByteArray): ByteArray? =
        parseTlvs(payload)?.get(0x01)?.takeIf { it.size == GROUP_ID_BYTES }
}

private const val GROUP_ID_BYTES = 16
private const val GROUP_KEY_BYTES = 32
private const val FINGERPRINT_BYTES = 32
private const val SIGNATURE_BYTES = 64
private const val MAX_GROUP_MEMBERS = 16
private const val MAX_GROUP_MESSAGE_BYTES = 4_096
private const val MAX_EPOCH = 0xFFFF_FFFFL

private fun encodeRoster(members: List<MeshGroupMember>): ByteArray? {
    if (members.isEmpty() || members.size > MAX_GROUP_MEMBERS) return null
    val output = ByteArrayOutputStream().apply { write(members.size) }
    val fingerprints = mutableSetOf<String>()
    members.forEach { member ->
        val fingerprint = member.fingerprint.hexToBytes() ?: return null
        val nickname = member.nickname.toByteArray(Charsets.UTF_8)
        if (fingerprint.size != FINGERPRINT_BYTES || member.signingKey.size != 32 ||
            nickname.size > 64 || !fingerprints.add(member.fingerprint)
        ) return null
        output.write(fingerprint)
        output.write(member.signingKey)
        output.write(nickname.size)
        output.write(nickname)
    }
    return output.toByteArray()
}

private fun decodeRoster(data: ByteArray): List<MeshGroupMember>? {
    if (data.isEmpty()) return null
    val input = java.nio.ByteBuffer.wrap(data)
    val count = input.get().toInt() and 0xFF
    if (count !in 1..MAX_GROUP_MEMBERS) return null
    val members = mutableListOf<MeshGroupMember>()
    val fingerprints = mutableSetOf<String>()
    repeat(count) {
        if (input.remaining() < 65) return null
        val fingerprint = ByteArray(32).also(input::get).hex()
        val signingKey = ByteArray(32).also(input::get)
        val nicknameLength = input.get().toInt() and 0xFF
        if (nicknameLength > input.remaining() || !fingerprints.add(fingerprint)) return null
        val nicknameBytes = ByteArray(nicknameLength).also(input::get)
        val nickname = nicknameBytes.toString(Charsets.UTF_8).takeIf {
            it.toByteArray(Charsets.UTF_8).contentEquals(nicknameBytes)
        } ?: return null
        members += MeshGroupMember(fingerprint, signingKey, nickname)
    }
    return members.takeIf { !input.hasRemaining() }
}

private fun encodeTlvs(fields: List<Pair<Int, ByteArray>>): ByteArray =
    ByteArrayOutputStream().apply {
        fields.forEach { (type, value) ->
            require(value.size <= 0xFFFF)
            write(type)
            write(value.size ushr 8)
            write(value.size)
            write(value)
        }
    }.toByteArray()

private fun parseTlvs(data: ByteArray): Map<Int, ByteArray>? {
    val fields = mutableMapOf<Int, ByteArray>()
    var offset = 0
    while (offset < data.size) {
        if (offset + 3 > data.size) return null
        val type = data[offset++].toInt() and 0xFF
        val length = ((data[offset++].toInt() and 0xFF) shl 8) or (data[offset++].toInt() and 0xFF)
        if (offset + length > data.size || type in fields) return null
        fields[type] = data.copyOfRange(offset, offset + length)
        offset += length
    }
    return fields
}

private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
    val cipher = ChaCha20Poly1305()
    cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val output = ByteArray(cipher.getOutputSize(input.size))
    val count = cipher.processBytes(input, 0, input.size, output, 0)
    val finalCount = cipher.doFinal(output, count)
    return output.copyOf(count + finalCount)
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun Long.toUInt32Bytes(): ByteArray {
    require(this in 0..MAX_EPOCH)
    return byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte()
    )
}

private fun ByteArray.toUInt32(): Long? = takeIf { size == 4 }?.fold(0L) { value, byte ->
    (value shl 8) or (byte.toLong() and 0xFF)
}

private fun Long.toUInt64Bytes(): ByteArray {
    require(this >= 0)
    return ByteArray(8) { index -> (this ushr ((7 - index) * 8)).toByte() }
}

private fun ByteArray.toUInt64(): Long? {
    if (size != 8 || this[0].toInt() and 0x80 != 0) return null
    return fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
}

private fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private object MessageIdentityVerifier {
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
        verifier.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
