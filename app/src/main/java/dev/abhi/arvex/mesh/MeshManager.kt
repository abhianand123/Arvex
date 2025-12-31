package dev.abhi.arvex.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class MeshManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "MeshManager"
    private val SERVICE_ID = "dev.abhi.arvex.mesh"
    private val STRATEGY = Strategy.P2P_CLUSTER

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    // State
    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints = _connectedEndpoints.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    val discoveredEndpoints = _discoveredEndpoints.asStateFlow()

    private val _meshState = MutableStateFlow(MeshState.IDLE)
    val meshState = _meshState.asStateFlow()

    // Events
    private val _incomingPayloads = MutableSharedFlow<MeshPayload>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingPayloads = _incomingPayloads.asSharedFlow()

    enum class MeshState { IDLE, ADVERTISING, DISCOVERING, CONNECTED }
    data class DiscoveredEndpoint(val id: String, val info: DiscoveredEndpointInfo)

    // Self Info
    val myEndpointId: String = "User_${Random.nextInt(1000, 9999)}" // Temp ID logic
    var roomCode: String? = null

    // Callbacks
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val meshPayload = MeshPayload.fromBytes(bytes) ?: return
                
                Log.d(TAG, "Received payload from $endpointId: $meshPayload")
                
                // Routing Logic
                if (meshPayload.targetId == "ALL" || meshPayload.targetId == myEndpointId) {
                     // Process locally
                     CoroutineScope(Dispatchers.IO).launch {
                         _incomingPayloads.emit(meshPayload)
                     }
                }

                // Chain Forwarding: If I am not the *only* target, and I have other neighbors, forward it.
                // For "ALL", forward to everyone except sender.
                // For specific ID, forward if I am not the target.
                if (meshPayload.targetId == "ALL") {
                    broadcastPayload(meshPayload, excludeEndpointId = endpointId) // Gossip flood
                } else if (meshPayload.targetId != myEndpointId) {
                    // Route to specific (Simple flood for now, optimization later)
                    broadcastPayload(meshPayload, excludeEndpointId = endpointId)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection Initiated: $endpointId")
            // Auto-accept for now (or prompt user in rigorous app, but usually auto-accept in trusted room flow)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "Connected to: $endpointId")
                _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                _meshState.value = MeshState.CONNECTED
                
                // Stop advertising/discovery if we strictly want 1-hop entry, but for Mesh/Cluster
                // we usually keep advertising if we want to grow the cluster, or stop if satisfied.
                // Arvex request: "Chain". 
                // We keep it open or stop depending on flow. For now, keep state as CONNECTED.
            } else {
                Log.e(TAG, "Connection failed: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected: $endpointId")
            _connectedEndpoints.value = _connectedEndpoints.value - endpointId
            if (_connectedEndpoints.value.isEmpty()) {
                _meshState.value = MeshState.IDLE // Or revert to Advertising?
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found endpoint: $endpointId, name=${info.endpointName}")
            if (info.serviceId == SERVICE_ID) {
                _discoveredEndpoints.value = _discoveredEndpoints.value + DiscoveredEndpoint(endpointId, info)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredEndpoints.value = _discoveredEndpoints.value.filter { it.id != endpointId }
        }
    }

    // Public API

    fun createRoom() {
        roomCode = Random.nextInt(1000, 9999).toString()
        startAdvertising(roomCode!!)
    }

    private fun startAdvertising(code: String) {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            code, // We use the room code as the "Name" for simplicity in discovery
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started for Room: $code")
            _meshState.value = MeshState.ADVERTISING
        }.addOnFailureListener {
            Log.e(TAG, "Advertising failed", it)
        }
    }

    fun startScanning() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
            _meshState.value = MeshState.DISCOVERING
        }.addOnFailureListener {
            Log.e(TAG, "Discovery failed", it)
        }
    }

    fun joinRoom(endpoint: DiscoveredEndpoint) {
        connectionsClient.requestConnection(
            myEndpointId,
            endpoint.id,
            connectionLifecycleCallback
        )
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _meshState.value = MeshState.IDLE
        _connectedEndpoints.value = emptyList()
        _discoveredEndpoints.value = emptyList()
    }

    fun sendPayload(payload: MeshPayload, targetEndpointId: String? = null) {
        val bytes = payload.toBytes()
        val p = Payload.fromBytes(bytes)
        
        if (targetEndpointId != null) {
            connectionsClient.sendPayload(targetEndpointId, p)
        } else {
            // Send to all connected
            if (_connectedEndpoints.value.isNotEmpty()) {
                connectionsClient.sendPayload(_connectedEndpoints.value, p)
            }
        }
    }

    private fun broadcastPayload(payload: MeshPayload, excludeEndpointId: String? = null) {
        val bytes = payload.toBytes()
        val targets = _connectedEndpoints.value.filter { it != excludeEndpointId }
        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(bytes))
        }
    }
}
