package com.bitchat.droid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

internal class MeshGroupStore(
    private val identityStore: IdentityStore,
    private val identity: MeshIdentity
) {
    private val groups = mutableListOf<MeshGroup>()

    init {
        load()
    }

    fun all(): List<MeshGroup> = groups.toList()

    fun find(groupId: ByteArray): MeshGroup? =
        groups.firstOrNull { it.groupId.contentEquals(groupId) }

    fun create(name: String, nickname: String): MeshGroup? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_GROUP_NAME_LENGTH) return null
        val member = MeshGroupMember(identity.noiseFingerprint, identity.signingPublic.copyOf(), nickname)
        val group = MeshGroup(
            groupId = ByteArray(GROUP_ID_BYTES).also(SecureRandom()::nextBytes),
            name = trimmed,
            key = ByteArray(GROUP_KEY_BYTES).also(SecureRandom()::nextBytes),
            epoch = 1,
            members = listOf(member),
            creatorFingerprint = identity.noiseFingerprint
        )
        return save(group)
    }

    fun rotate(groupId: ByteArray, members: List<MeshGroupMember>): MeshGroup? {
        val current = find(groupId) ?: return null
        if (current.creatorFingerprint != identity.noiseFingerprint || current.epoch >= MAX_EPOCH) return null
        val updated = current.copy(
            key = ByteArray(GROUP_KEY_BYTES).also(SecureRandom()::nextBytes),
            epoch = current.epoch + 1,
            members = members
        )
        return save(updated)
    }

    fun apply(state: MeshGroupState): ApplyResult {
        if (!state.verify()) return ApplyResult.REJECTED
        val existing = find(state.groupId)
        if (existing != null) {
            val existingCreator = existing.members.firstOrNull { it.fingerprint == existing.creatorFingerprint }
            val incomingCreator = state.members.firstOrNull { it.fingerprint == state.creatorFingerprint }
            if (existing.creatorFingerprint != state.creatorFingerprint ||
                existingCreator == null || incomingCreator == null ||
                !existingCreator.signingKey.contentEquals(incomingCreator.signingKey) ||
                state.epoch < existing.epoch
            ) return ApplyResult.REJECTED
        }
        if (state.members.none { it.fingerprint == identity.noiseFingerprint }) {
            if (existing != null) {
                groups.remove(existing)
                persist()
                return ApplyResult.REMOVED
            }
            return ApplyResult.REJECTED
        }

        val isNew = existing == null
        if (save(state.asGroup()) == null) return ApplyResult.REJECTED
        return if (isNew) ApplyResult.ADDED else ApplyResult.UPDATED
    }

    fun remove(groupId: ByteArray) {
        groups.removeAll { it.groupId.contentEquals(groupId) }
        persist()
    }

    private fun save(group: MeshGroup): MeshGroup? {
        val state = stateFor(group) ?: return null
        if (!state.verify()) return null
        val persistedGroup = group.copy(signature = state.signature.copyOf())
        val index = groups.indexOfFirst { it.groupId.contentEquals(group.groupId) }
        if (index >= 0) groups[index] = persistedGroup else groups += persistedGroup
        persist()
        return persistedGroup
    }

    private fun persist() {
        val states = groups.map { stateFor(it)?.encode() ?: error("Unable to encode saved group state.") }
        require(states.size <= MAX_STORED_GROUPS)
        val output = ByteArrayOutputStream().apply {
            write(states.size ushr 8)
            write(states.size)
            states.forEach { state ->
                require(state.size <= 0xFFFF)
                write(state.size ushr 8)
                write(state.size)
                write(state)
            }
        }.toByteArray()
        identityStore.saveEncryptedGroupData(output)
    }

    private fun stateFor(group: MeshGroup): MeshGroupState? =
        if (group.creatorFingerprint == identity.noiseFingerprint) {
            MeshGroupState.create(group, identity)
        } else {
            MeshGroupState(
                group.groupId,
                group.name,
                group.key,
                group.epoch,
                group.members,
                group.creatorFingerprint,
                group.signature
            ).takeIf { it.verify() }
        }

    private fun load() {
        val encoded = identityStore.loadEncryptedGroupData() ?: return
        try {
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            if (input.remaining() < 2) return
            val count = input.short.toInt() and 0xFFFF
            if (count > MAX_STORED_GROUPS) return
            repeat(count) {
                if (input.remaining() < 2) return
                val size = input.short.toInt() and 0xFFFF
                if (size > input.remaining()) return
                val bytes = ByteArray(size).also(input::get)
                val state = MeshGroupState.decode(bytes) ?: return@repeat
                if (state.members.any { it.fingerprint == identity.noiseFingerprint } &&
                    groups.none { it.groupId.contentEquals(state.groupId) }
                ) groups += state.asGroup()
            }
        } catch (_: Exception) {
            groups.clear()
        }
    }

    enum class ApplyResult { ADDED, UPDATED, REMOVED, REJECTED }

    companion object {
        private const val GROUP_ID_BYTES = 16
        private const val GROUP_KEY_BYTES = 32
        private const val MAX_GROUP_NAME_LENGTH = 40
        private const val MAX_STORED_GROUPS = 128
        private const val MAX_EPOCH = 0xFFFF_FFFFL
    }
}
