package com.bitchat.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class MeshProtocolTest {
    @Test
    fun v1PacketMatchesTheSharedBigEndianWireLayout() {
        val packet = MeshPacket(
            type = MeshMessageType.MESSAGE,
            senderId = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            timestamp = 1,
            payload = byteArrayOf('h'.code.toByte(), 'i'.code.toByte()),
            ttl = 7
        )

        assertEquals(
            "010207000000000000000100000200010203040506076869",
            MeshPacketCodec.encode(packet, padding = false)?.hex()
        )
    }

    @Test
    fun v2RouteRoundTripsWithoutChangingTheRecipientOrHops() {
        val packet = MeshPacket(
            type = MeshMessageType.MESSAGE,
            senderId = ByteArray(8) { it.toByte() },
            timestamp = 0x0102030405060708,
            payload = byteArrayOf(0x41),
            ttl = 5,
            recipientId = ByteArray(8) { (it + 8).toByte() },
            version = 2,
            route = listOf(ByteArray(8) { (it + 16).toByte() })
        )

        val encoded = MeshPacketCodec.encode(packet, padding = false)
        val decoded = encoded?.let(MeshPacketCodec::decode)
        assertNotNull(decoded)
        assertEquals(2, decoded?.version)
        assertEquals(5, decoded?.ttl)
        assertEquals(packet.senderId.hex(), decoded?.senderId?.hex())
        assertEquals(packet.recipientId?.hex(), decoded?.recipientId?.hex())
        assertEquals(packet.route.first().hex(), decoded?.route?.first()?.hex())
        assertEquals("A", decoded?.payload?.toString(Charsets.UTF_8))
    }

    @Test
    fun packetSignatureUsesTheSharedTtlIndependentCanonicalBytes() {
        val key = Ed25519PrivateKeyParameters(SecureRandom())
        val packet = MeshPacket(
            type = MeshMessageType.MESSAGE,
            senderId = ByteArray(8) { 0x2A },
            timestamp = 1234,
            payload = "offline group chat".toByteArray(),
            ttl = 7
        )
        val identity = MeshIdentity(
            noisePrivate = ByteArray(32),
            noisePublic = ByteArray(32),
            signingPrivate = key.encoded,
            signingPublic = key.generatePublicKey().encoded
        )
        val signature = MeshPacketCodec.signingBytes(packet)?.let(identity::sign)
        assertNotNull(signature)
        assertTrue(identity.verify(MeshPacketCodec.signingBytes(packet.copy(ttl = 3))!!, signature!!, identity.signingPublic))
        assertFalse(
            identity.verify(
                MeshPacketCodec.signingBytes(packet.copy(payload = "altered".toByteArray()))!!,
                signature,
                identity.signingPublic
            )
        )
    }

    @Test
    fun imageTlvRoundTripsAndRejectsMimeMagicMismatch() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val file = MeshFilePacket("img_test.jpg", "image/jpeg", jpeg)

        assertEquals(jpeg.hex(), MeshFilePacket.decode(file.encode())?.content?.hex())
        assertEquals("img_test.jpg", MeshFilePacket.decode(file.encode())?.fileName)
        val mismatchedMimePayload = byteArrayOf(0x01, 0x00, 0x07) +
            "bad.png".toByteArray() +
            byteArrayOf(0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x03) +
            byteArrayOf(0x03, 0x00, 0x09) +
            "image/png".toByteArray() +
            byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x03) +
            jpeg
        assertEquals(null, MeshFilePacket.decode(mismatchedMimePayload))
    }

    @Test
    fun imageTlvMatchesTheIosCanonicalEncoding() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val packet = MeshFilePacket("img_test.jpg", "image/jpeg", jpeg)

        assertEquals(
            "01000c696d675f746573742e6a7067" +
                "02000400000003" +
                "03000a696d6167652f6a706567" +
                "0400000003ffd8ff",
            packet.encode().hex()
        )
    }

    @Test
    fun releaseMetadataSignatureRejectsPayloadTampering() {
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(SecureRandom())
        val payload = """{"version":"2.0.0"}""".toByteArray()
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(payload, 0, payload.size)
        }
        val signature = signer.generateSignature()
        val publicKey = privateKey.generatePublicKey().encoded

        assertTrue(ReleaseSignatureVerifier.verify(payload, signature, publicKey))
        assertFalse(ReleaseSignatureVerifier.verify(payload + 0x20, signature, publicKey))
    }

    @Test
    fun frameAssemblerStripsPaddingAndKeepsPacketBoundary() {
        val packet = MeshPacket(
            type = MeshMessageType.ANNOUNCE,
            senderId = ByteArray(8),
            timestamp = 22,
            payload = byteArrayOf(1, 2),
            ttl = 7
        )
        val frame = MeshPacketCodec.encode(packet)!!
        val assembler = MeshFrameAssembler()
        val firstHalf = assembler.append(frame.copyOfRange(0, 20))
        val secondHalf = assembler.append(frame.copyOfRange(20, frame.size))
        assertTrue(firstHalf.isEmpty())
        assertEquals(1, secondHalf.size)
        assertEquals(packet.payload.hex(), MeshPacketCodec.decode(secondHalf.single())?.payload?.hex())
    }
}
