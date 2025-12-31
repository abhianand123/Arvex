package dev.abhi.arvex.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
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

@Singleton
class SpatialAwarenessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meshManager: MeshManager
) : SensorEventListener {
    private val TAG = "SpatialAwarenessManager"

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // State
    private val _myLocation = MutableStateFlow<Location?>(null)
    val myLocation = _myLocation.asStateFlow()

    private val _myAzimuth = MutableStateFlow(0f)
    val myAzimuth = _myAzimuth.asStateFlow()

    // Peer Locations (Stored for UI)
    // Key: EndpointID
    private val _peerLocations = MutableStateFlow<Map<String, LocationInfo>>(emptyMap())
    val peerLocations = _peerLocations.asStateFlow()

    data class LocationInfo(val lat: Double, val lon: Double, val azimuth: Float, val timestamp: Long)

    private var broadcastJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Sensor vars
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    init {
        // Listen for incoming location updates
        CoroutineScope(Dispatchers.IO).launch {
            meshManager.incomingPayloads.collect { payload ->
                if (payload is LocationPayload) {
                    val info = LocationInfo(payload.lat, payload.lon, payload.azimuth, payload.timestamp)
                    _peerLocations.value = _peerLocations.value + (payload.senderId to info)
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions handled by caller/UI
    fun startAwareness() {
        Log.d(TAG, "Starting Spatial Awareness")
        
        // 1. Location Updates
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
            
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        // 2. Compass Updates
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }

        // 3. Broadcast Loop
        broadcastJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                broadcastMyPosition()
            }
        }
    }

    fun stopAwareness() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        broadcastJob?.cancel()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(p0: LocationResult) {
            for (location in p0.locations) {
                _myLocation.value = location
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }
        updateOrientationAngles()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        // orientationAngles[0] is azimuth in radians
        val azimuthDeg = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
        _myAzimuth.value = azimuthDeg
    }

    private fun broadcastMyPosition() {
        val loc = _myLocation.value ?: return
        val azi = _myAzimuth.value
        
        val payload = LocationPayload(
            senderId = meshManager.myEndpointId,
            lat = loc.latitude,
            lon = loc.longitude,
            azimuth = azi
        )
        meshManager.sendPayload(payload)
    }

    // Math Utils for UI
    fun calculateDistance(targetLat: Double, targetLon: Double): Float {
        val myLoc = _myLocation.value ?: return -1f
        val result = FloatArray(1)
        Location.distanceBetween(myLoc.latitude, myLoc.longitude, targetLat, targetLon, result)
        return result[0]
    }

    fun calculateBearing(targetLat: Double, targetLon: Double): Float {
        val myLoc = _myLocation.value ?: return 0f
        val targetLoc = Location("").apply { latitude = targetLat; longitude = targetLon }
        return myLoc.bearingTo(targetLoc)
    }
}
