package dev.abhi.arvex.cast

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage

class CastMediaItemConverter : MediaItemConverter {
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        
        mediaItem.mediaMetadata.title?.let {
            metadata.putString(MediaMetadata.KEY_TITLE, it.toString())
        }
        mediaItem.mediaMetadata.artist?.let {
            metadata.putString(MediaMetadata.KEY_ARTIST, it.toString())
        }
        mediaItem.mediaMetadata.albumTitle?.let {
            metadata.putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString())
        }
        mediaItem.mediaMetadata.artworkUri?.let {
            metadata.addImage(WebImage(it))
        }

        val contentUrl = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.mediaId

        val mediaInfo = MediaInfo.Builder(contentUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypes.AUDIO_MPEG) // Defaulting to mp3, but should ideally come from metadata
            .setMetadata(metadata)
            .build()

        return MediaQueueItem.Builder(mediaInfo)
            .setAutoplay(true)
            .build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val mediaInfo = mediaQueueItem.media
        val metadata = androidx.media3.common.MediaMetadata.Builder()
        
        mediaInfo?.metadata?.let { castMetadata ->
            metadata.setTitle(castMetadata.getString(MediaMetadata.KEY_TITLE))
            metadata.setArtist(castMetadata.getString(MediaMetadata.KEY_ARTIST))
            metadata.setAlbumTitle(castMetadata.getString(MediaMetadata.KEY_ALBUM_TITLE))
            castMetadata.images.firstOrNull()?.url?.let { metadata.setArtworkUri(it) }
        }

        return MediaItem.Builder()
            .setMediaId(mediaInfo?.contentId ?: "")
            .setUri(mediaInfo?.contentId)
            .setMediaMetadata(metadata.build())
            .build()
    }
}
