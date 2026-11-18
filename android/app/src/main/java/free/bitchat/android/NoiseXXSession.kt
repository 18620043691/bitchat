package com.bitchat.droid

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class NoiseXXSession(
    private val localStaticPrivate: ByteArray,
    val role: Role,
    prologue: ByteArray = ByteArray(0),
    predeterminedEphemeralPrivateKeys: List<ByteArray> = emptyList()
) {
    enum class Role { INITIATOR, RESPONDER }

    private val patterns = listOf(
        listOf(Token.E),
        listOf(Token.E, Token.EE, Token.S, Token.ES),
        listOf(Token.S, Token.SE)
    )
    private val symmetric = SymmetricState("Noise_XX_25519_ChaChaPoly_SHA256", prologue)
    private val fixedEphemeralKeys = predeterminedEphemeralPrivateKeys.map { it.copyOf() }
    private var patternIndex = 0
    private var ephemeralIndex = 0
    private var started = false
    private var localEphemeralPrivate: ByteArray? = null
    private var remoteEphemeralPublic: ByteArray? = null
    private var remoteStaticPublic: ByteArray? = null
    private var sendCipher: TransportCipher? = null
    private var receiveCipher: TransportCipher? = null

    val isEstablished: Boolean
        get() = sendCipher != null && receiveCipher != null

    val remoteStaticKey: ByteArray?
        get() = remoteStaticPublic?.copyOf()

    var lastHandshakePayload: ByteArray = ByteArray(0)
        private set

    fun startHandshake(payload: ByteArray = ByteArray(0)): ByteArray {
        check(role == Role.INITIATOR && !started) { "Noise handshake cannot be started in this state." }
        started = true
        return writeMessage(payload)
    }

    fun processHandshakeMessage(
        message: ByteArray,
        responsePayload: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (!started) {
            check(role == Role.RESPONDER) { "Noise responder state is unavailable." }
            started = true
        }
        check(!isEstablished && patternIndex < patterns.size) { "Noise handshake is already complete." }

        lastHandshakePayload = readMessage(message)
        if (patternIndex == patterns.size) {
            establish()
            return null
        }

        val response = writeMessage(responsePayload)
        if (patternIndex == patterns.size) establish()
        return response
    }

    fun encrypt(plaintext: ByteArray): ByteArray =
        checkNotNull(sendCipher) { "Noise session is not established." }.encrypt(plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray =
        checkNotNull(receiveCipher) { "Noise session is not established." }.decrypt(ciphertext)

    private fun writeMessage(payload: ByteArray): ByteArray {
        check(patternIndex < patterns.size)
        val message = ByteArrayOutputStream()
        for (token in patterns[patternIndex]) {
            when (token) {
                Token.E -> {
                    val ephemeralPrivate = fixedEphemeralKeys.getOrNull(ephemeralIndex)
                        ?: X25519PrivateKeyParameters(SecureRandom()).encoded
                    ephemeralIndex++
                    val ephemeral = X25519PrivateKeyParameters(ephemeralPrivate, 0)
                    localEphemeralPrivate = ephemeral.encoded
                    val public = ephemeral.generatePublicKey().encoded
                    message.write(public)
                    symmetric.mixHash(public)
                }
                Token.S -> {
                    val public = X25519PrivateKeyParameters(localStaticPrivate, 0).generatePublicKey().encoded
                    message.write(symmetric.encryptAndHash(public))
                }
                Token.EE -> mixDh(localEphemeralPrivate, remoteEphemeralPublic)
                Token.ES -> if (role == Role.INITIATOR) {
                    mixDh(localEphemeralPrivate, remoteStaticPublic)
                } else {
                    mixDh(localStaticPrivate, remoteEphemeralPublic)
                }
                Token.SE -> if (role == Role.INITIATOR) {
                    mixDh(localStaticPrivate, remoteEphemeralPublic)
                } else {
                    mixDh(localEphemeralPrivate, remoteStaticPublic)
                }
            }
        }
        message.write(symmetric.encryptAndHash(payload))
        patternIndex++
        return message.toByteArray()
    }

    private fun readMessage(message: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(message)
        for (token in patterns[patternIndex]) {
            when (token) {
                Token.E -> {
                    require(input.remaining() >= 32) { "Truncated Noise ephemeral key." }
                    val public = ByteArray(32).also(input::get)
                    remoteEphemeralPublic = public
                    symmetric.mixHash(public)
                }
                Token.S -> {
                    val length = if (symmetric.hasCipherKey()) 48 else 32
                    require(input.remaining() >= length) { "Truncated Noise static key." }
                    val encrypted = ByteArray(length).also(input::get)
                    val public = symmetric.decryptAndHash(encrypted)
                    require(public.size == 32) { "Invalid Noise static key." }
                    remoteStaticPublic = public
                }
                Token.EE -> mixDh(localEphemeralPrivate, remoteEphemeralPublic)
                Token.ES -> if (role == Role.INITIATOR) {
                    mixDh(localEphemeralPrivate, remoteStaticPublic)
                } else {
                    mixDh(localStaticPrivate, remoteEphemeralPublic)
                }
                Token.SE -> if (role == Role.INITIATOR) {
                    mixDh(localStaticPrivate, remoteEphemeralPublic)
                } else {
                    mixDh(localEphemeralPrivate, remoteStaticPublic)
                }
            }
        }
        val payload = ByteArray(input.remaining()).also(input::get)
        val plaintext = symmetric.decryptAndHash(payload)
        patternIndex++
        return plaintext
    }

    private fun mixDh(privateKey: ByteArray?, publicKey: ByteArray?) {
        val localKey = privateKey ?: throw IllegalArgumentException("Noise handshake is missing a DH key.")
        val remoteKey = publicKey ?: throw IllegalArgumentException("Noise handshake is missing a DH key.")
        require(localKey.size == 32 && remoteKey.size == 32) { "Noise handshake contains an invalid DH key." }
        val secret = ByteArray(32)
        X25519PrivateKeyParameters(localKey, 0).generateSecret(X25519PublicKeyParameters(remoteKey, 0), secret, 0)
        require(secret.any { it != 0.toByte() }) { "Invalid all-zero X25519 shared secret." }
        symmetric.mixKey(secret)
        secret.fill(0)
    }

    private fun establish() {
        val keys = symmetric.split()
        localEphemeralPrivate?.fill(0)
        localEphemeralPrivate = null
        if (role == Role.INITIATOR) {
            sendCipher = TransportCipher(keys.first)
            receiveCipher = TransportCipher(keys.second)
        } else {
            sendCipher = TransportCipher(keys.second)
            receiveCipher = TransportCipher(keys.first)
        }
    }

    private enum class Token { E, S, EE, ES, SE }

    private class SymmetricState(protocolName: String, prologue: ByteArray) {
        private var chainingKey: ByteArray
        private var handshakeHash: ByteArray
        private var cipherKey: ByteArray? = null
        private var nonce = 0L

        init {
            val name = protocolName.toByteArray(Charsets.UTF_8)
            handshakeHash = if (name.size <= 32) name + ByteArray(32 - name.size) else sha256(name)
            chainingKey = handshakeHash.copyOf()
            mixHash(prologue)
        }

        fun hasCipherKey(): Boolean = cipherKey != null

        fun mixHash(data: ByteArray) {
            handshakeHash = sha256(handshakeHash + data)
        }

        fun mixKey(inputKeyMaterial: ByteArray) {
            val output = hkdf(chainingKey, inputKeyMaterial, 2)
            chainingKey = output[0]
            cipherKey = output[1]
            nonce = 0
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val key = cipherKey
            val output = if (key == null) {
                plaintext
            } else {
                aead(true, key, noiseNonce(nonce), handshakeHash, plaintext).also { nonce++ }
            }
            mixHash(output)
            return output
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val key = cipherKey
            val output = if (key == null) {
                ciphertext
            } else {
                aead(false, key, noiseNonce(nonce), handshakeHash, ciphertext).also { nonce++ }
            }
            mixHash(ciphertext)
            return output
        }

        fun split(): Pair<ByteArray, ByteArray> {
            val output = hkdf(chainingKey, ByteArray(0), 2)
            cipherKey?.fill(0)
            chainingKey.fill(0)
            handshakeHash.fill(0)
            return output[0] to output[1]
        }

        companion object {
            private fun sha256(data: ByteArray): ByteArray =
                MessageDigest.getInstance("SHA-256").digest(data)

            private fun hkdf(chainingKey: ByteArray, input: ByteArray, count: Int): List<ByteArray> {
                val prk = hmac(chainingKey, input)
                val result = mutableListOf<ByteArray>()
                var previous = ByteArray(0)
                repeat(count) { index ->
                    previous = hmac(prk, previous + byteArrayOf((index + 1).toByte()))
                    result += previous
                }
                prk.fill(0)
                return result
            }

            private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key, "HmacSHA256"))
                return mac.doFinal(data)
            }

            private fun noiseNonce(value: Long): ByteArray =
                ByteArray(12).also { bytes ->
                    for (index in 0 until 8) bytes[4 + index] = (value ushr (index * 8)).toByte()
                }

            private fun aead(
                encrypt: Boolean,
                key: ByteArray,
                nonce: ByteArray,
                aad: ByteArray,
                input: ByteArray
            ): ByteArray {
                val cipher = ChaCha20Poly1305()
                cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
                val output = ByteArray(cipher.getOutputSize(input.size))
                val length = cipher.processBytes(input, 0, input.size, output, 0)
                val finalLength = cipher.doFinal(output, length)
                return output.copyOf(length + finalLength)
            }
        }
    }

    private class TransportCipher(private val key: ByteArray) {
        private var sendNonce = 0L
        private var highestReceivedNonce = 0L
        private val receivedNonces = linkedSetOf<Long>()

        fun encrypt(plaintext: ByteArray): ByteArray {
            check(sendNonce <= 0xFFFF_FFFEL) { "Noise transport nonce exhausted." }
            val nonce = sendNonce++
            val ciphertext = aead(true, key, noiseNonce(nonce), ByteArray(0), plaintext)
            return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nonce.toInt()).array() + ciphertext
        }

        fun decrypt(packet: ByteArray): ByteArray {
            require(packet.size >= 20) { "Truncated Noise transport ciphertext." }
            val nonce = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            require(
                !(highestReceivedNonce >= REPLAY_WINDOW && nonce <= highestReceivedNonce - REPLAY_WINDOW) &&
                    nonce !in receivedNonces
            ) { "Noise transport replay rejected." }
            val plaintext = aead(false, key, noiseNonce(nonce), ByteArray(0), packet.copyOfRange(4, packet.size))
            receivedNonces += nonce
            if (nonce > highestReceivedNonce) highestReceivedNonce = nonce
            receivedNonces.removeAll { it < highestReceivedNonce && highestReceivedNonce - it >= REPLAY_WINDOW }
            return plaintext
        }

        private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
            val cipher = ChaCha20Poly1305()
            cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val output = ByteArray(cipher.getOutputSize(input.size))
            val length = cipher.processBytes(input, 0, input.size, output, 0)
            val finalLength = cipher.doFinal(output, length)
            return output.copyOf(length + finalLength)
        }

        private fun noiseNonce(value: Long): ByteArray =
            ByteArray(12).also { bytes ->
                for (index in 0 until 8) bytes[4 + index] = (value ushr (index * 8)).toByte()
            }

        companion object {
            private const val REPLAY_WINDOW = 1024L
        }
    }
}
