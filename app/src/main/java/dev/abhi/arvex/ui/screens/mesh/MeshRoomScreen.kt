package dev.abhi.arvex.ui.screens.mesh

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.abhi.arvex.mesh.MeshManager
import dev.abhi.arvex.mesh.SpatialAwarenessManager


import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshRoomScreen(
    navController: NavController,
    meshManager: MeshManager,
    spatialManager: SpatialAwarenessManager
) {
    val context = LocalContext.current
    val meshState by meshManager.meshState.collectAsState()
    val discoveredEndpoints by meshManager.discoveredEndpoints.collectAsState()
    val connectedEndpoints by meshManager.connectedEndpoints.collectAsState()
    
    // Permission Handling
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Room") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (meshState != MeshManager.MeshState.IDLE) {
                        IconButton(onClick = { 
                            meshManager.stopAll() 
                            spatialManager.stopAwareness()
                        }) {
                            Icon(Icons.Default.Refresh, "Reset")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasPermissions) {
                Text("Permissions required for Mesh Networking.")
                Button(onClick = { hasPermissions = false }) { Text("Grant Permissions") }
                return@Column
            }

            when {
                meshState == MeshManager.MeshState.IDLE -> {
                    IdleView(
                        onCreate = {
                            meshManager.createRoom()
                            spatialManager.startAwareness()
                        },
                        onScan = {
                            meshManager.startScanning()
                            spatialManager.startAwareness()
                        }
                    )
                }
                meshState == MeshManager.MeshState.ADVERTISING -> {
                    RoomCodeView(meshManager.roomCode ?: "....")
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("Waiting for peers...")
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    
                    if (connectedEndpoints.isNotEmpty()) {
                         RadarView(spatialManager, connectedEndpoints)
                    }
                }
                meshState == MeshManager.MeshState.DISCOVERING -> {
                    ScanningView(
                        endpoints = discoveredEndpoints,
                        onConnect = { meshManager.joinRoom(it) }
                    )
                }
                meshState == MeshManager.MeshState.CONNECTED -> {
                    Text("Connected to Room", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    RadarView(spatialManager, connectedEndpoints)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("${connectedEndpoints.size} Peer(s) Active")
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            meshManager.stopAll()
                            spatialManager.stopAwareness()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Leave Room")
                    }
                }
            }
        }
    }
}

@Composable
fun IdleView(onCreate: () -> Unit, onScan: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clickable { onCreate() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Create Room", style = MaterialTheme.typography.headlineSmall)
                Text("Be the Host and control playback", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onScan() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Sensors, null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Join Room", style = MaterialTheme.typography.headlineSmall)
                Text("Scan for nearby rooms", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun RoomCodeView(code: String) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ROOM CODE", style = MaterialTheme.typography.labelMedium)
            Text(
                text = code,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )
        }
    }
}

@Composable
fun ScanningView(
    endpoints: List<MeshManager.DiscoveredEndpoint>,
    onConnect: (MeshManager.DiscoveredEndpoint) -> Unit
) {
    Text("Scanning for nearby rooms...", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
    
    LazyColumn {
        items(endpoints) { endpoint ->
            ListItem(
                headlineContent = { Text("Room: ${endpoint.info.endpointName}") },
                supportingContent = { Text("ID: ${endpoint.id}") },
                leadingContent = { Icon(Icons.Default.Radar, null) },
                trailingContent = { Button(onClick = { onConnect(endpoint) }) { Text("Join") } }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun RadarView(spatialManager: SpatialAwarenessManager, connectedPeers: List<String>) {
    // A customized Canvas to show "Self" in center and Peers relative to distance/bearing
    val peerLocations by spatialManager.peerLocations.collectAsState()
    val myAzimuth by spatialManager.myAzimuth.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.1f))
    ) {
       Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
           // Radar Rings
           Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
               val center = center
               val radius = size.minDimension / 2
               
               // Draw rings
               drawCircle(Color.Gray, radius, style = Stroke(width = 2f))
               drawCircle(Color.Gray, radius * 0.66f, style = Stroke(width = 2f))
               drawCircle(Color.Gray, radius * 0.33f, style = Stroke(width = 2f))
               
               // Draw Self (Center)
               drawCircle(Color.Green, 15f, center)
               
               // Draw FOV / Compass (My Azimuth)
               // Note: If we rotate the map so "North" is up, we draw peers absolute.
               // If we rotate map so "Heading" is up, we rotate peers.
               // Let's keep "North" up for simplicity, and show an arrow for my heading.
               val compassAngleRad = Math.toRadians((myAzimuth - 90).toDouble()) // -90 to align 0 with East (Standard math) -> North
               // Actually North is -90 deg in standard circle math (top).
               // Azimuth 0 = North. 90 = East.
               // Canvas 0 deg = East. 90 = South. -90 = North.
               // So correct angle for drawing = (Azimuth - 90)
               
               val arrowEnd = Offset(
                   center.x + (20f * sin(Math.toRadians(myAzimuth.toDouble()))).toFloat(), // Correct trig for North=0, Clockwise
                   center.y - (20f * cos(Math.toRadians(myAzimuth.toDouble()))).toFloat()
               )
               drawLine(Color.Green, center, arrowEnd, strokeWidth = 5f)

               // Draw Peers
               connectedPeers.forEach { peerId ->
                   val loc = peerLocations[peerId]
                   if (loc != null) {
                       val dist = spatialManager.calculateDistance(loc.lat, loc.lon)
                       val bearing = spatialManager.calculateBearing(loc.lat, loc.lon)
                       
                       // Map distance to canvas radius. Max range 20m?
                       val maxRange = 20f 
                       val plottedDist = (dist / maxRange).coerceIn(0f, 1f) * radius
                       
                       // Bearing 0 = North.
                       val angleRad = Math.toRadians((bearing - 90).toDouble())
                       
                       // Standard Trig: x = r*cos(a), y = r*sin(a) where a=0 is East.
                       // Bearing: 0 is North (Top), 90 is East (Right).
                       // We need to convert Bearing to Standard Angle.
                       // Standard = 90 - Bearing?
                       // North (0) -> 90. East (90) -> 0. South (180) -> -90. West (270) -> -180.
                       val drawAngleRad = Math.toRadians((90 - bearing).toDouble())

                       val peerX = center.x + (plottedDist * cos(drawAngleRad)).toFloat()
                       val peerY = center.y - (plottedDist * sin(drawAngleRad)).toFloat()
                       
                       drawCircle(Color.Red, 10f, Offset(peerX, peerY))
                   }
               }
           }
       }
    }
}
