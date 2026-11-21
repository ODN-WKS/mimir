package io.github.odnwks.mimir.data.store

import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn

typealias ChatId = String
typealias ModelId = String

/** A chat as stored (alias for the existing UI model; both sides use LocalChat). */
typealias Chat = LocalChat

/** Summary row in the chat list. */
data class ChatSummary(val id: ChatId, val title: String, val at: Long)

/**
 * The phone's cryptographic identity: the link key and the paired PC (PHD-01).
 * Kept in a file entirely separate from chat data so a chat corruption can never wipe the pairing.
 */
data class Identity(
    /** The iroh private key for the encrypted link. */
    val key: ByteArray,
    /** The paired PC's id (null: not paired yet). */
    val pcId: String? = null,
    /** The name shown for the PC on this phone. */
    val pcName: String? = null,
) {
    override fun equals(other: Any?) =
        other is Identity && other.pcId == pcId && other.pcName == pcName && other.key.contentEquals(key)
    override fun hashCode() = pcId.hashCode() * 31 + key.contentHashCode()
}
