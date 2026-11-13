package com.smartbackpack.display.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import com.smartbackpack.display.model.MusicTrack

class MediaNotificationListener : NotificationListenerService() {

    private val tag = "MediaListener"
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    companion object {
        var onTrackChanged: ((MusicTrack) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "MediaNotificationListener created")
        setupMediaSessionManager()
    }

    private fun setupMediaSessionManager() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, this::class.java)

            mediaSessionManager?.addOnActiveSessionsChangedListener({ controllers ->
                Log.d(tag, "Active media sessions changed: ${controllers?.size ?: 0} sessions")
                updateActiveController(controllers)
            }, componentName)

            // Initial query
            val initialControllers = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveController(initialControllers)
        } catch (e: SecurityException) {
            Log.e(tag, "Notification access permission not granted yet: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            activeController = null
            return
        }

        // Prefer active playing controller
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()

        if (activeController?.packageName != playingController.packageName) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = playingController
            activeController?.registerCallback(controllerCallback)
            Log.d(tag, "Attached to media session: ${activeController?.packageName}")
        }

        dispatchCurrentMetadata()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            dispatchCurrentMetadata()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            dispatchCurrentMetadata()
        }
    }

    private fun dispatchCurrentMetadata() {
        val controller = activeController ?: return
        val metadata = controller.metadata ?: return
        val state = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val positionMs = state?.position ?: 0
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        // Extract artwork bitmap
        val artworkBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val track = MusicTrack(
            title = title,
            artist = artist,
            album = album,
            duration = if (durationMs > 0) durationMs / 1000 else 0,
            position = positionMs / 1000,
            playing = isPlaying,
            artwork = artworkBitmap
        )

        Log.d(tag, "Broadcasting track: $title by $artist (playing: $isPlaying)")
        onTrackChanged?.invoke(track)
    }

    override fun onDestroy() {
        activeController?.unregisterCallback(controllerCallback)
        super.onDestroy()
    }
}
