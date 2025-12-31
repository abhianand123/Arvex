package dev.abhi.arvex.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class MeshPayload {
    abstract val senderId: String
    abstract val targetId: String // "ALL" or specific EndpointID
    abstract val timestamp: Long

    fun toBytes(): ByteArray {
        return Json.encodeToString(this).toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MeshPayload? {
             return try {
                 Json.decodeFromString<MeshPayload>(bytes.toString(Charsets.UTF_8))
             } catch (e: Exception) {
                 e.printStackTrace()
                 null
             }
        }
    }
}

@Serializable
data class SyncAudioPayload(
    override val senderId: String,
    override val targetId: String = "ALL",
    override val timestamp: Long = System.currentTimeMillis(),
    val audioId: String,
    val positionMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val targetPlayTimeMs: Long // The future time this state applies (for "Play At" precision)
) : MeshPayload()

@Serializable
data class LocationPayload(
    override val senderId: String,
    override val targetId: String = "ALL",
    override val timestamp: Long = System.currentTimeMillis(),
    val lat: Double,
    val lon: Double,
    val azimuth: Float
) : MeshPayload()

@Serializable
data class TimeSyncPayload(
    override val senderId: String,
    override val targetId: String, // Must be specific
    override val timestamp: Long = System.currentTimeMillis(), // Client's t0 or Server's t1
    val type: Type,
    val serverReceiveTime: Long = 0, // t1
    val serverSendTime: Long = 0     // t2
) : MeshPayload() {
    enum class Type { REQUEST, RESPONSE }
}

@Serializable
data class ChatPayload(
    override val senderId: String,
    override val targetId: String = "ALL",
    override val timestamp: Long = System.currentTimeMillis(),
    val message: String
) : MeshPayload()
