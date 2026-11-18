package com.bitchat.droid

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupProtocolTest {
    @Test
    fun signedGroupStateRoundTripsAndRetainsCreatorSignature() {
        val creator = identity(1)
        val group = MeshGroup(
            groupId = ByteArray(16) { it.toByte() },
            name = "Test group",
            key = ByteArray(32) { (it + 1).toByte() },
            epoch = 1,
            members = listOf(
                MeshGroupMember(creator.noiseFingerprint, creator.signingPublic, "Creator")
            ),
            creatorFingerprint = creator.noiseFingerprint
        )

        val state = checkNotNull(MeshGroupState.create(group, creator))
        val decoded = MeshGroupState.decode(checkNotNull(state.encode()))
        assertNotNull(decoded)
        val decodedState = checkNotNull(decoded)
        assertTrue(decodedState.verify())
        assertArrayEquals(state.signature, decodedState.asGroup().signature)
    }

    @Test
    fun groupStateCannotBeSignedByANonCreator() {
        val creator = identity(1)
        val other = identity(2)
        val group = MeshGroup(
            groupId = ByteArray(16),
            name = "Test group",
            key = ByteArray(32),
            epoch = 1,
            members = listOf(MeshGroupMember(creator.noiseFingerprint, creator.signingPublic, "Creator")),
            creatorFingerprint = creator.noiseFingerprint
        )

        assertNull(MeshGroupState.create(group, other))
    }

    @Test
    fun encryptedGroupMessageRoundTripsForMembers() {
        val sender = identity(1)
        val group = MeshGroup(
            groupId = ByteArray(16) { (it + 3).toByte() },
            name = "Test group",
            key = ByteArray(32) { (it + 7).toByte() },
            epoch = 2,
            members = listOf(MeshGroupMember(sender.noiseFingerprint, sender.signingPublic, "Sender")),
            creatorFingerprint = sender.noiseFingerprint
        )
        val payload = MeshGroupMessageCodec.seal(
            group = group,
            content = "Offline message",
            identity = sender,
            nickname = "Sender",
            messageId = "message-1",
            timestamp = 1_700_000_000_000
        )

        val opened = MeshGroupMessageCodec.open(payload, group, sender.signingPublic)
        assertNotNull(opened)
        val decoded = checkNotNull(opened)
        assertEquals("Offline message", decoded.text)
        assertEquals("message-1", decoded.messageId)
    }

    private fun identity(seed: Int): MeshIdentity {
        val noisePrivate = X25519PrivateKeyParameters(ByteArray(32) { (it + seed).toByte() }, 0)
        val signingPrivate = Ed25519PrivateKeyParameters(ByteArray(32) { (it + seed * 3).toByte() }, 0)
        return MeshIdentity(
            noisePrivate.encoded,
            noisePrivate.generatePublicKey().encoded,
            signingPrivate.encoded,
            signingPrivate.generatePublicKey().encoded
        )
    }
}
