package com.bitchat.droid

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class NoiseXXSessionTest {
    @Test
    fun xxHandshakeMatchesIosNoiseVector() {
        val prologue = hex("4a6f686e2047616c74")
        val alicePrivate = hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
        val aliceEphemeral = hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
        val bobPrivate = hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
        val bobEphemeral = hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
        val alice = NoiseXXSession(
            alicePrivate,
            NoiseXXSession.Role.INITIATOR,
            prologue,
            listOf(aliceEphemeral)
        )
        val bob = NoiseXXSession(
            bobPrivate,
            NoiseXXSession.Role.RESPONDER,
            prologue,
            listOf(bobEphemeral)
        )

        val payload1 = "Ludwig von Mises".toByteArray()
        val message1 = alice.startHandshake(payload1)
        assertArrayEquals(
            hex("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"),
            message1
        )
        val payload2 = "Murray Rothbard".toByteArray()
        val message2 = requireNotNull(bob.processHandshakeMessage(message1, payload2))
        assertArrayEquals(payload1, bob.lastHandshakePayload)
        assertArrayEquals(
            hex("95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884381cbad1f276e038c48378ffce2b65285e08d6b68aaa3629a5a8639392490e5b9bd5269c2f1e4f488ed8831161f19b7815528f8982ffe09be9b5c412f8a0db50f8814c7194e83f23dbd8d162c9326ad"),
            message2
        )
        val payload3 = "F. A. Hayek".toByteArray()
        val message3 = requireNotNull(alice.processHandshakeMessage(message2, payload3))
        assertArrayEquals(payload2, alice.lastHandshakePayload)
        assertArrayEquals(
            hex("c7195ffacac1307ff99046f219750fc47693e23c3cb08b89c2af808b444850a80ae475b9df0f169ae80a89be0865b57f58c9fea0d4ec82a286427402f113e4b6ae769a1d95941d49b25030"),
            message3
        )
        assertNull(bob.processHandshakeMessage(message3))
        assertArrayEquals(payload3, bob.lastHandshakePayload)
        assertTrue(alice.isEstablished)
        assertTrue(bob.isEstablished)
    }

    @Test
    fun xxHandshakeAndTransportCipherInteroperate() {
        val alicePrivate = X25519PrivateKeyParameters(SecureRandom()).encoded
        val bobPrivate = X25519PrivateKeyParameters(SecureRandom()).encoded
        val alice = NoiseXXSession(alicePrivate, NoiseXXSession.Role.INITIATOR)
        val bob = NoiseXXSession(bobPrivate, NoiseXXSession.Role.RESPONDER)

        assertFalse(alice.isEstablished)
        val message1 = alice.startHandshake()
        val message2 = requireNotNull(bob.processHandshakeMessage(message1))
        val message3 = requireNotNull(alice.processHandshakeMessage(message2))
        assertTrue(alice.isEstablished)
        assertNull(bob.processHandshakeMessage(message3))
        assertTrue(bob.isEstablished)

        assertArrayEquals(
            X25519PrivateKeyParameters(bobPrivate, 0).generatePublicKey().encoded,
            requireNotNull(alice.remoteStaticKey)
        )
        assertArrayEquals(
            X25519PrivateKeyParameters(alicePrivate, 0).generatePublicKey().encoded,
            requireNotNull(bob.remoteStaticKey)
        )

        val alicePlaintext = "private message".toByteArray()
        val aliceCiphertext = alice.encrypt(alicePlaintext)
        assertArrayEquals(alicePlaintext, bob.decrypt(aliceCiphertext))

        val bobPlaintext = "reply".toByteArray()
        val bobCiphertext = bob.encrypt(bobPlaintext)
        assertArrayEquals(bobPlaintext, alice.decrypt(bobCiphertext))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transportReplayIsRejected() {
        val alicePrivate = X25519PrivateKeyParameters(SecureRandom()).encoded
        val bobPrivate = X25519PrivateKeyParameters(SecureRandom()).encoded
        val alice = NoiseXXSession(alicePrivate, NoiseXXSession.Role.INITIATOR)
        val bob = NoiseXXSession(bobPrivate, NoiseXXSession.Role.RESPONDER)

        val message1 = alice.startHandshake()
        val message2 = requireNotNull(bob.processHandshakeMessage(message1))
        val message3 = requireNotNull(alice.processHandshakeMessage(message2))
        bob.processHandshakeMessage(message3)

        val ciphertext = alice.encrypt("once".toByteArray())
        bob.decrypt(ciphertext)
        bob.decrypt(ciphertext)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
