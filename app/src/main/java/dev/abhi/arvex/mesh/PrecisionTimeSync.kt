package dev.abhi.arvex.mesh

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PrecisionTimeSync @Inject constructor(
    private val meshManager: MeshManager
) {
    private val TAG = "PrecisionTimeSync"
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // The offset to add to local Sytem.currentTimeMillis() to get "Mesh Time" (Host Time)
    // If we are Host, offset is 0.
    private val _timeOffset = MutableStateFlow(0L)
    val timeOffset = _timeOffset.asStateFlow()

    private val _rtt = MutableStateFlow(0L)
    val rtt = _rtt.asStateFlow()

    private var syncJob: Job? = null
    var isHost = false
    var hostEndpointId: String? = null // Set when we join a room or create one

    init {
        scope.launch {
            meshManager.incomingPayloads.collect { payload ->
                if (payload is TimeSyncPayload) {
                    handleTimePayload(payload)
                }
            }
        }
        
        // Monitor connections
        scope.launch {
            meshManager.connectedEndpoints.collect { peers ->
                if (!isHost && hostEndpointId == null && peers.isNotEmpty()) {
                    // Primitive Host specific logic: The first peer we connect to is assumed Host in this simplistic model
                    // Or we need an explicit "Handshake" to determine Host. 
                    // For now, let's assume the Creator is Host. 
                    // But we don't know who is Creator from just ID. 
                    // Improvement: Add 'isHost' flag to MeshPayload wrapper or handshake.
                    // Implementation: We will trigger sync manually or when we identify a host.
                    // Ideally, the "Room Code" holder is Host. We will assume the advertiser is the host.
                    // But connectionsClient callbacks don't explicitly say "You connected to Advertiser".
                }
            }
        }
    }

    fun startSync(targetHostId: String) {
        hostEndpointId = targetHostId
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                sendSyncRequest()
                delay(5000) // Sync every 5s
            }
        }
    }
    
    fun stopSync() {
        syncJob?.cancel()
        _timeOffset.value = 0
    }

    private fun sendSyncRequest() {
        val host = hostEndpointId ?: return
        val t0 = System.currentTimeMillis()
        val payload = TimeSyncPayload(
            senderId = meshManager.myEndpointId,
            targetId = host,
            timestamp = t0,
            type = TimeSyncPayload.Type.REQUEST
        )
        // Send direct to host, don't broadcast if possible, but meshManager handles routing
        // If we are indirect, it relies on forwarding.
        meshManager.sendPayload(payload)
    }

    private fun handleTimePayload(payload: TimeSyncPayload) {
        if (payload.targetId != meshManager.myEndpointId) return // Not for me (already forwarded by MeshManager if needed)

        if (payload.type == TimeSyncPayload.Type.REQUEST) {
            // I am the Server/Host for this request
            val t1 = System.currentTimeMillis()
            // Process...
            val t2 = System.currentTimeMillis()
            
            val response = payload.copy(
                senderId = meshManager.myEndpointId,
                targetId = payload.senderId,
                timestamp = payload.timestamp, // Echo back t0
                type = TimeSyncPayload.Type.RESPONSE,
                serverReceiveTime = t1,
                serverSendTime = t2
            )
            meshManager.sendPayload(response)

        } else if (payload.type == TimeSyncPayload.Type.RESPONSE) {
            // I am the Client, received response
            val t3 = System.currentTimeMillis()
            val t0 = payload.timestamp
            val t1 = payload.serverReceiveTime
            val t2 = payload.serverSendTime
            
            val roundTrip = (t3 - t0) - (t2 - t1)
            val clockOffset = ((t1 - t0) + (t2 - t3)) / 2
            
            _rtt.value = roundTrip
            _timeOffset.value = clockOffset
            
            Log.v(TAG, "Sync Update: Offset=$clockOffset ms, RTT=$roundTrip ms")
        }
    }
    
    fun getMeshTime(): Long {
        return System.currentTimeMillis() + _timeOffset.value
    }
}
