/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package dev.abhi.arvex.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import dev.abhi.arvex.MainActivity
import dev.abhi.arvex.R
import dev.abhi.arvex.constants.AudioDecoderKey
import dev.abhi.arvex.constants.AudioGaplessOffloadKey
import dev.abhi.arvex.constants.AudioNormalizationKey
import dev.abhi.arvex.constants.AudioOffloadKey
import dev.abhi.arvex.constants.AudioQuality
import dev.abhi.arvex.constants.AudioQualityKey
import dev.abhi.arvex.constants.AutoLoadMoreKey
import dev.abhi.arvex.constants.ENABLE_FFMETADATAEX
import dev.abhi.arvex.constants.KeepAliveKey
import dev.abhi.arvex.constants.MAX_PLAYER_CONSECUTIVE_ERR
import dev.abhi.arvex.constants.MaxQueuesKey
import dev.abhi.arvex.constants.MediaSessionConstants.CommandToggleLike
import dev.abhi.arvex.constants.MediaSessionConstants.CommandToggleRepeatMode
import dev.abhi.arvex.constants.MediaSessionConstants.CommandToggleShuffle
import dev.abhi.arvex.constants.MediaSessionConstants.CommandToggleStartRadio
import dev.abhi.arvex.constants.PauseListenHistoryKey
import dev.abhi.arvex.constants.PauseRemoteListenHistoryKey
import dev.abhi.arvex.constants.PersistentQueueKey
import dev.abhi.arvex.constants.PlayerVolumeKey
import dev.abhi.arvex.constants.RepeatModeKey
import dev.abhi.arvex.constants.SkipOnErrorKey
import dev.abhi.arvex.constants.SkipSilenceKey
import dev.abhi.arvex.constants.StopMusicOnTaskClearKey
import dev.abhi.arvex.constants.minPlaybackDurKey
import dev.abhi.arvex.db.MusicDatabase
import dev.abhi.arvex.db.entities.Event
import dev.abhi.arvex.db.entities.FormatEntity
import dev.abhi.arvex.db.entities.RelatedSongMap
import dev.abhi.arvex.di.AppModule.PlayerCache
import dev.abhi.arvex.di.DownloadCache
import dev.abhi.arvex.extensions.SilentHandler
import dev.abhi.arvex.extensions.collect
import dev.abhi.arvex.extensions.collectLatest
import dev.abhi.arvex.extensions.currentMetadata
import dev.abhi.arvex.extensions.findNextMediaItemById
import dev.abhi.arvex.extensions.metadata
import dev.abhi.arvex.extensions.setOffloadEnabled
import dev.abhi.arvex.lyrics.LyricsHelper
import dev.abhi.arvex.models.HybridCacheDataSinkFactory
import dev.abhi.arvex.models.MediaMetadata
import dev.abhi.arvex.models.MultiQueueObject
import dev.abhi.arvex.models.toMediaMetadata
import dev.abhi.arvex.extensions.toMediaItem
import dev.abhi.arvex.playback.queues.ListQueue
import dev.abhi.arvex.playback.queues.Queue
import dev.abhi.arvex.playback.queues.YouTubeQueue
import dev.abhi.arvex.utils.CoilBitmapLoader
import dev.abhi.arvex.utils.NetworkConnectivityObserver
import dev.abhi.arvex.utils.SyncUtils
import dev.abhi.arvex.utils.YTPlayerUtils
import dev.abhi.arvex.utils.dataStore
import dev.abhi.arvex.utils.enumPreference
import dev.abhi.arvex.utils.get
import dev.abhi.arvex.utils.playerCoroutine
import dev.abhi.arvex.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.abhi.arvex.cast.CastMediaItemConverter
import dev.abhi.arvex.cast.CastOptionsProvider
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.abs
import dev.abhi.arvex.mesh.MeshManager
import dev.abhi.arvex.mesh.SpatialAwarenessManager
import dev.abhi.arvex.mesh.PrecisionTimeSync
import dev.abhi.arvex.mesh.SyncAudioPayload


@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback,
    SessionManagerListener<CastSession> {
    val TAG = MusicService::class.simpleName.toString()

    @Inject
    lateinit var database: MusicDatabase
    private val scope = CoroutineScope(Dispatchers.Main)
    private val offloadScope = CoroutineScope(playerCoroutine)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var meshManager: MeshManager

    @Inject
    lateinit var spatialManager: SpatialAwarenessManager

    @Inject
    lateinit var timeSync: PrecisionTimeSync

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var localPlayer: ExoPlayer
    var castPlayer: CastPlayer? = null
    private var _currentPlayer: Player? = null
    val player: Player
        get() = _currentPlayer ?: localPlayer

    private val _playerFlow = MutableStateFlow<Player?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private lateinit var mediaSession: MediaLibrarySession

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder = dataStore.get(AudioDecoderKey, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    // Cache for resolved URLs to avoid re-fetching and rate limiting
    private val songUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    override fun onCreate() {
        Log.i(TAG, "Starting MusicService")
        super.onCreate()

        localPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setSkipSilenceEnabled(dataStore.get(SkipSilenceKey, false))
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                _playerFlow.value = this

                // misc
                if (dataStore.get(AudioOffloadKey, false)) {
                     // cast to ExoPlayer or use extension if available, but setOffloadEnabled is usually extension
                     // Assuming setOffloadEnabled is an extension function on ExoPlayer
                     setOffloadEnabled(true)
                }
            }

        // Cast Init
        try {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext, CastMediaItemConverter())
            castPlayer?.addListener(this)
            castContext.sessionManager.addSessionManagerListener(this, CastSession::class.java)
        } catch (e: Exception) {
             Log.e(TAG, "Cast Init failed", e)
             // Fallback if Cast not available
        }

        _currentPlayer = localPlayer

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        localPlayer.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        currentSong.collect(scope) {
            updateNotification()
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // lateinit tasks
        offloadScope.launch {
            Log.i(TAG, "Launching MusicService offloadScope tasks")
            if (!qbInit.value) {
                initQueue()
            }

            combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
                playerVolume * normalizeFactor
            }.collectLatest(scope) {
                withContext(Dispatchers.Main) {
                    player.volume = it
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                         if (player !is CastPlayer) {
                            localPlayer.skipSilenceEnabled = it
                         }
                    }
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { format, normalizeAudio ->
                format to normalizeAudio
            }.collectLatest(scope) { (format, normalizeAudio) ->
                normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
            }


            // network connectivity
            try {
                connectivityObserver.unregister()
            } catch (e: UninitializedPropertyAccessException) {
                // lol
            }
            connectivityObserver = NetworkConnectivityObserver(this@MusicService)

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        waitingForNetworkConnection.value = false
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
            
            // Mesh Sync Listener
            // Listen for new connections to send initial state ONLY to them
            offloadScope.launch {
                var previousEndpoints = emptyList<String>()
                meshManager.connectedEndpoints.collect { currentEndpoints ->
                    val newEndpoints = currentEndpoints - previousEndpoints.toSet()
                    if (newEndpoints.isNotEmpty()) {
                        // A new peer joined! Send them the current state directly.
                        newEndpoints.forEach { newPeerId ->
                            Log.d(TAG, "New peer joined: $newPeerId. Sending initial sync state.")
                            launch(Dispatchers.Main) {
                                val mediaId = player.currentMediaItem?.mediaId
                                if (mediaId != null) {
                                    val payload = SyncAudioPayload(
                                        senderId = meshManager.myEndpointId,
                                        targetId = newPeerId, // Target specific peer
                                        audioId = mediaId,
                                        positionMs = player.currentPosition,
                                        isPlaying = player.isPlaying,
                                        playbackSpeed = player.playbackParameters.speed,
                                        targetPlayTimeMs = 0
                                    )
                                    // Send payload (IO safe)
                                    withContext(Dispatchers.IO) {
                                        meshManager.sendPayload(payload, targetEndpointId = newPeerId)
                                    }
                                }
                            }
                        }
                    }
                    previousEndpoints = currentEndpoints
                }
            }

            offloadScope.launch {
                meshManager.incomingPayloads.collect { payload ->
                    if (payload is SyncAudioPayload) {
                         if (payload.senderId == meshManager.myEndpointId) return@collect
                         
                         withContext(Dispatchers.Main) {
                             if (player.currentMediaItem?.mediaId != payload.audioId) {
                                  // Switch Song
                                  Log.d(TAG, "Mesh Sync: Switching to new song ${payload.audioId}")
                                  val song = withContext(Dispatchers.IO) {
                                      // Try Local DB
                                      database.song(payload.audioId).first()?.toMediaMetadata() ?: run {
                                          // Try Network Fetch
                                          try {
                                              val response = YTPlayerUtils.playerResponseForMetadata(payload.audioId).getOrNull()
                                              response?.videoDetails?.let { details ->
                                                  MediaMetadata(
                                                      id = details.videoId,
                                                      title = details.title,
                                                      artists = listOf(MediaMetadata.Artist(id = details.author, name = details.author)),
                                                      thumbnailUrl = details.thumbnail.thumbnails.maxByOrNull { it.width ?: 0 }?.url,
                                                      duration = details.lengthSeconds.toInt(),
                                                      genre = null
                                                  )
                                              }
                                          } catch (e: Exception) {
                                              null
                                          }
                                      }
                                  } ?: MediaMetadata(
                                      id = payload.audioId,
                                      title = "Syncing...",
                                      artists = listOf(MediaMetadata.Artist(id = null, name = "Mesh Network")),
                                      thumbnailUrl = null,
                                      duration = -1,
                                      genre = null
                                  )
                                  
                                  // Create a 1-item queue and play
                                  playQueue(
                                      queue = ListQueue(
                                          title = "Synced Session",
                                          items = listOf(song)
                                      ),
                                      playWhenReady = true
                                  )
                                  
                                  return@withContext 
                              }
                             
                             val myTime = timeSync.getMeshTime()
                             val elapsedSinceSend = myTime - payload.timestamp
                             val targetPos = payload.positionMs + (if (payload.isPlaying) elapsedSinceSend * payload.playbackSpeed else 0).toLong()
                             
                             val currentPos = player.currentPosition
                             val diff = targetPos - currentPos
                             
                             if (abs(diff) > 50) {
                                 if (abs(diff) > 2000) {
                                     player.seekTo(targetPos)
                                 } else {
                                     // Micro adjustment
                                     // If we are BEHIND (diff > 0), speed up.
                                     // If we are AHEAD (diff < 0), slow down.
                                     val newSpeed = if (diff > 0) 1.05f else 0.95f
                                     player.setPlaybackSpeed(newSpeed)
                                     
                                     // Revert speed after catch up (approx 1 sec later)
                                     scope.launch {
                                         delay(1000)
                                         player.setPlaybackSpeed(payload.playbackSpeed)
                                     }
                                 }
                             } else {
                                 if (player.playbackParameters.speed != payload.playbackSpeed) {
                                     player.setPlaybackSpeed(payload.playbackSpeed)
                                 }
                             }

                             if (payload.isPlaying && !player.isPlaying) player.play()
                             if (!payload.isPlaying && player.isPlaying) player.pause()
                         }
                    }
                }
            }
        }
    }
    

    
    private var lastBroadcastTime = 0L
    private fun broadcastSyncState(force: Boolean = false) {
        if (meshManager.connectedEndpoints.value.isEmpty()) return
        
        // Debounce slightly to avoid flood during rapid seeks, but keep it snappy
        val now = System.currentTimeMillis()
        if (!force && now - lastBroadcastTime < 250) return // Reduced debounce from 500ms to 250ms for snappier seeking
        lastBroadcastTime = now
        
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val payload = SyncAudioPayload(
            senderId = meshManager.myEndpointId,
            audioId = mediaId,
            positionMs = player.currentPosition,
            isPlaying = player.isPlaying,
            playbackSpeed = player.playbackParameters.speed,
            targetPlayTimeMs = 0 // Immediate for now
        )
        meshManager.sendPayload(payload)
    }


// Library functions

    private suspend fun recoverSong(mediaId: String, playbackData: YTPlayerUtils.PlaybackData? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }


// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        if (!qbInit.value) {
            runBlocking(Dispatchers.IO) {
                initQueue()
            }
        }

        var queueTitle = title
        queuePlaylistId = queue.playlistId
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        // do not use scope.launch ... it breaks randomly... why is this bug back???
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                if (preloadItem != null) {
                    q = queueBoard.addQueue(
                        queueTitle ?: "Radio\u2060temp",
                        listOf(preloadItem),
                        shuffled = queue.startShuffled,
                        replace = replace,
                        continuationEndpoint = null // fulfilled later on after initial status
                    )
                    queueBoard.setCurrQueue(q, true)
                }

                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                // do not find a title if an override is provided
                if ((title == null) && initialStatus.title != null) {
                    queueTitle = initialStatus.title

                    if (preloadItem != null && q != null) {
                        queueBoard.renameQueue(q!!, queueTitle)
                    }
                }

                val items = ArrayList<MediaMetadata>()
                Log.d(TAG, "playQueue: Queue initial status item count: ${initialStatus.items.size}")
                if (!initialStatus.items.isEmpty()) {
                    if (preloadItem != null) {
                        items.add(preloadItem)
                        items.addAll(initialStatus.items.subList(1, initialStatus.items.size))
                    } else {
                        items.addAll(initialStatus.items)
                    }
                    val q = queueBoard.addQueue(
                        queueTitle ?: getString(R.string.queue),
                        items,
                        shuffled = queue.startShuffled,
                        startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                        replace = replace || preloadItem != null,
                        continuationEndpoint = if (isRadio) items.takeLast(4).shuffled().first().id else null // yq?.getContinuationEndpoint()
                    )
                    queueBoard.setCurrQueue(q, shouldResume)
                }

                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }

            Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {

                // when enqueuing next when player isn't active, play as a new song
                if (items.isNotEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata }
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let {
                    queueBoard.addSongsToQueue(it, player.currentMediaItemIndex + 1, items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        queueBoard.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
        queueBoard.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.shuffleCurrent()
        } else {
            queueBoard.unShuffleCurrent()
        }
        queueBoard.setCurrQueue()

        updateNotification()
    }

    suspend fun initQueue() {
        Log.i(TAG, "+initQueue()")
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)
        if (persistQueue) {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, database.readQueue().toMutableList(), maxQueues)
        } else {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)
        }
        Log.d(TAG, "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}")
        qbInit.value = true
        Log.i(TAG, "-initQueue()")
    }

    fun deInitQueue() {
        Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.getAllQueues()
        data.last().lastSongPos = currentPosition
        database.updateAllQueues(data)
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, localPlayer.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, localPlayer.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun createCacheDataSource(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .build()
                            )
                        )
                    )
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")?.isLocal == true
                            Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Log.d(TAG, "PLAYING: song id = $mediaId")

            var song = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")
            if (song == null) { // in the case of resumption, queueBoard may not be ready yet
                song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            }
            // local song
            if (song?.localPath != null) {
                if (song.isLocal) {
                    Log.d(TAG, "PLAYING: local song")
                    val file = File(song.localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return@Factory dataSpec.withUri(file.toUri())
                } else {
                    val isDownloadNew = downloadUtil.localMgr.getFilePathIfExists(mediaId)
                    isDownloadNew?.let {
                        Log.d(TAG, "PLAYING: Custom downloaded song")
                        return@Factory dataSpec.withUri(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
            val isCache = playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            if (isDownload || isCache) {
                Log.d(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                Log.d(TAG, "PLAYING: remote song (temp cache)")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            Log.d(TAG, "PLAYING: remote song (online fetch)")

            val playbackData = runBlocking(Dispatchers.IO) {
                val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            val streamUrl = playbackData.streamUrl

            songUrlCache[mediaId] =
                streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
            dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (queueBoard.getCurrentQueue()?.shuffled == true) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG).show()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(this@MusicService, getString(R.string.err_play_next_on_error), Toast.LENGTH_SHORT).show()
            return
        }

        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_too_many_errors), Toast.LENGTH_LONG).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG).show()
    }


// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // wait for reconnection
        val isConnectionError = (error.cause?.cause is PlaybackException)
                && (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }

        Toast.makeText(
            this@MusicService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""} ",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            val pos = player.currentPosition
            val q = queueBoard.getCurrentQueue()
            q?.lastSongPos = pos
        }
        super.onIsPlayingChanged(isPlaying)
        broadcastSyncState()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        broadcastSyncState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        broadcastSyncState(force = true) // Force update on song change
        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }

        // Auto load more songs
        val q = queueBoard.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                q.playlistId = mediaItems.takeLast(4).shuffled().first().id // yq.getContinuationEndpoint()
                Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                if (player.playbackState != STATE_IDLE && songCount > 1) { // initial radio loading is handled by playQueue()
                    queueBoard.enqueueEnd(mediaItems.drop(1))
                }
            }
        }

        queueBoard.setCurrQueuePosIndex(player.currentMediaItemIndex)

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.shuffleCurrent(player.mediaItemCount > 2)
                queueBoard.setCurrQueue()
            }
        }

        updateNotification() // also updates when queue changes
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == STATE_IDLE) {
            queuePlaylistId = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }

    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        offloadScope.launch {
            val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

            val playRatio =
                playbackStats.totalPlayTimeMs.toFloat() / ((mediaItem.metadata?.duration?.times(1000)) ?: -1)
            Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur")
            if (playRatio >= minPlaybackDur && !dataStore.get(PauseListenHistoryKey, false)) {
                database.query {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                    } catch (_: SQLException) {
                    }
                }

                // TODO: support playlist id
                val ytHist = mediaItem.metadata?.isLocal != true && !dataStore.get(PauseRemoteListenHistoryKey, false)
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val playbackUrl = YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                        .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        
        // Apply shuffle to ExoPlayer (needs custom shuffle order)
        if (player is ExoPlayer) {
             (player as ExoPlayer).setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        }
        
        // CastPlayer handles shuffle internally, just set the mode
        player.shuffleModeEnabled = shuffleModeEnabled
        
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // listeners
        val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
        if (skipSilenceEnabled && !(player is CastPlayer)) {
            localPlayer.skipSilenceEnabled = skipSilenceEnabled
        }
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Terminating MusicService.")
        deInitQueue()

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        super.onDestroy()
        Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }


    // Cast Session Management
    override fun onSessionStarting(session: CastSession) {}
    
    override fun onSessionStarted(session: CastSession, sessionId: String) {
        Log.d(TAG, "Cast Session Started")
        castPlayer?.let { switchPlayer(it) }
    }

    override fun onSessionStartFailed(session: CastSession, error: Int) {
        Log.e(TAG, "Cast Session Start Failed: $error")
    }

    override fun onSessionEnding(session: CastSession) {
        Log.d(TAG, "Cast Session Ending")
        // Don't switch back yet, wait for ended
    }

    override fun onSessionEnded(session: CastSession, error: Int) {
        Log.d(TAG, "Cast Session Ended")
        switchPlayer(localPlayer)
    }

    override fun onSessionResuming(session: CastSession, sessionId: String) {}

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        Log.d(TAG, "Cast Session Resumed")
        castPlayer?.let { switchPlayer(it) }
    }

    override fun onSessionResumeFailed(session: CastSession, error: Int) {}

    override fun onSessionSuspended(session: CastSession, reason: Int) {}

    private fun switchPlayer(newPlayer: Player) {
        if (_currentPlayer == newPlayer) return

        val previousPlayer = _currentPlayer ?: localPlayer
        
        // Save state
        var playbackPositionMs = C.TIME_UNSET
        var currentMediaItemIndex = 0
        var playWhenReady = previousPlayer.playWhenReady
        
        if (previousPlayer.playbackState != Player.STATE_IDLE && previousPlayer.playbackState != Player.STATE_ENDED) {
            playbackPositionMs = previousPlayer.currentPosition
            currentMediaItemIndex = previousPlayer.currentMediaItemIndex
        }
        val currentMediaItem = previousPlayer.currentMediaItem

        // Stop previous player
        previousPlayer.stop()
        previousPlayer.clearMediaItems()
        
        if (previousPlayer == localPlayer) {
            // Audio focus released by stop()
        }

        // Update current player
        _currentPlayer = newPlayer
        _playerFlow.value = newPlayer
        
        // Update MediaSession
        mediaSession.player = newPlayer

        // Get current queue from QueueBoard
        val currentQueue = queueBoard.getCurrentQueue()
        
        if (currentQueue != null && currentQueue.queue.isNotEmpty()) {
            // Snapshot the queue to avoid ConcurrentModificationException if the live queue changes
            // while we are resolving URLs in the background.
            val queueSnapshot = currentQueue.queue.toList()
            
            // We have a queue to transfer
            scope.launch {
                if (newPlayer == castPlayer) {
                    val itemsToMap = if (currentQueue.shuffled) currentQueue.getCurrentQueueShuffled() else currentQueue.queue
                    val itemsToTransfer = itemsToMap.map { it.toMediaItem() }
                    
                    val resolvedItems = resolveMediaItemsForCast(itemsToTransfer)
                    
                    if (resolvedItems.isNotEmpty()) {
                        var newStartIndex = 0
                        val currentSongId = currentQueue.getCurrentSong()?.id
                        if (currentSongId != null) {
                             val foundIndex = resolvedItems.indexOfFirst { it.mediaId == currentSongId }
                             if (foundIndex != -1) newStartIndex = foundIndex
                        }

                        newPlayer.setMediaItems(resolvedItems, newStartIndex, playbackPositionMs)
                        newPlayer.shuffleModeEnabled = false // Force linear playback as we provided a shuffled list
                        newPlayer.repeatMode = previousPlayer.repeatMode
                        newPlayer.prepare()
                        newPlayer.playWhenReady = playWhenReady
                    }
                } else {
                    val itemsToTransfer = queueSnapshot.map { it.toMediaItem() }
                    
                    // Find correct index in local queue using current song ID
                    var localStartIndex = 0
                    val currentSongId = currentQueue.getCurrentSong()?.id
                    if (currentSongId != null) {
                        val foundIndex = itemsToTransfer.indexOfFirst { it.mediaId == currentSongId }
                        if (foundIndex != -1) localStartIndex = foundIndex
                    }
                    
                    newPlayer.setMediaItems(itemsToTransfer, localStartIndex, playbackPositionMs)
                    newPlayer.shuffleModeEnabled = currentQueue.shuffled
                    newPlayer.repeatMode = previousPlayer.repeatMode
                    newPlayer.prepare()
                    newPlayer.playWhenReady = playWhenReady
                    
                    // Update UI state after player switch
                    updateNotification()
                }
            }
        } else if (currentMediaItem != null && playbackPositionMs != C.TIME_UNSET) {
            // Fallback: single item transfer
            scope.launch {
                if (newPlayer == castPlayer) {
                    val resolvedItems = resolveMediaItemsForCast(listOf(currentMediaItem))
                    if (resolvedItems.isNotEmpty()) {
                        newPlayer.setMediaItems(resolvedItems, 0, playbackPositionMs)
                        newPlayer.shuffleModeEnabled = previousPlayer.shuffleModeEnabled
                        newPlayer.repeatMode = previousPlayer.repeatMode
                        newPlayer.prepare()
                        newPlayer.playWhenReady = playWhenReady
                    }
                } else {
                    newPlayer.setMediaItems(listOf(currentMediaItem), 0, playbackPositionMs)
                    newPlayer.shuffleModeEnabled = previousPlayer.shuffleModeEnabled
                    newPlayer.repeatMode = previousPlayer.repeatMode
                    newPlayer.prepare()
                    newPlayer.playWhenReady = playWhenReady
                }
            }
        }
        // If no queue and no current item, newPlayer stays empty (idle state)
    }

    // Helper for URL Resolution
    suspend fun resolveMediaItemsForCast(items: List<MediaItem>): List<MediaItem> {
        // Process in chunks to avoid spamming network requests too hard
        return items.chunked(5).map { chunk ->
            chunk.map { item ->
                scope.async(Dispatchers.IO) {
                    val resolvedUrl = resolveMediaItem(item.mediaId)
                    if (resolvedUrl != null) {
                        item.buildUpon()
                            .setUri(resolvedUrl)
                            .build()
                    } else {
                        null
                    }
                }
            }.awaitAll()
        }.flatten().filterNotNull()
    }

    suspend fun resolveMediaItem(mediaId: String): String? {
            // Check cache first
            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                Log.d(TAG, "RESOLVE: Cache hit for $mediaId")
                return it.first
            }

            // Check db/local
            val song = database.song(mediaId).first()?.toMediaMetadata() ?: return null
            if (song.isLocal) {
                    Log.w(TAG, "Cannot cast local file without HTTP server: ${song.localPath}")
                    return null
            }

            // Remote
            return try {
                val playbackData = withContext(Dispatchers.IO) {
                    val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                    YTPlayerUtils.playerResponseForPlayback(
                        mediaId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                    )
                }.getOrThrow()
                
                val streamUrl = playbackData.streamUrl
                // Cache the result
                if (playbackData.streamExpiresInSeconds > 0) {
                    songUrlCache[mediaId] = streamUrl to (System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L))
                }
                
                streamUrl
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve URL for Cast", e)
                null
            }
    }


    // Facade for QueueBoard
    val isCasting: Boolean get() = castPlayer != null && _currentPlayer == castPlayer

    fun clearMediaItems() {
        player.clearMediaItems()
    }

    fun addMediaItems(items: List<MediaItem>) {
        if (isCasting) {
             scope.launch {
                  val resolved = resolveMediaItemsForCast(items)
                  if (resolved.isNotEmpty()) {
                       castPlayer?.addMediaItems(resolved)
                  }
             }
         } else {
             localPlayer.addMediaItems(items)
         }
    }

    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
         if (isCasting) {
             scope.launch {
                 val resolvedItems = resolveMediaItemsForCast(items)
                 // Find new startIndex if items were filtered
                 var newStartIndex = 0
                 val originalStartItem = items.getOrNull(startIndex)
                 if (originalStartItem != null) {
                     val foundIndex = resolvedItems.indexOfFirst { it.mediaId == originalStartItem.mediaId }
                     if (foundIndex != -1) newStartIndex = foundIndex
                 }

                 if (resolvedItems.isNotEmpty()) {
                      castPlayer?.setMediaItems(resolvedItems, newStartIndex, startPositionMs)
                      castPlayer?.prepare()
                      castPlayer?.play()
                 }
            }
         } else {
             localPlayer.setMediaItems(items, startIndex, startPositionMs)
             localPlayer.prepare()
             localPlayer.play()
         }
    }
    
    fun addMediaItems(index: Int, items: List<MediaItem>) {
         if (isCasting) {
             scope.launch {
                 val resolved = resolveMediaItemsForCast(items)
                 if (resolved.isNotEmpty()) {
                     castPlayer?.addMediaItems(index, resolved)
                 }
             }
         } else {
             localPlayer.addMediaItems(index, items)
         }
    }

    fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        player.removeMediaItems(fromIndex, toIndex)
    }
    
    fun replaceMediaItems(fromIndex: Int, toIndex: Int, items: List<MediaItem>) {
         if (isCasting) {
             scope.launch {
                 val resolved = resolveMediaItemsForCast(items)
                 castPlayer?.replaceMediaItems(fromIndex, toIndex, resolved)
             }
         } else {
             localPlayer.replaceMediaItems(fromIndex, toIndex, items)
         }
    }

    companion object {

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}
