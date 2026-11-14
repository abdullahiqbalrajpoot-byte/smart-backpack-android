package com.smartbackpack.display.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.smartbackpack.display.model.MusicTrack

class MediaNotificationListener : NotificationListenerService() {

    private val tag = "MediaListener"
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    companion object {
        var instance: MediaNotificationListener? = null
        var currentTrack: MusicTrack? = null
        var onTrackChanged: ((MusicTrack) -> Unit)? = null

        fun refresh() {
            instance?.scanActiveNotifications()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(tag, "MediaNotificationListener created")
        setupMediaSessionManager()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(tag, "MediaNotificationListener CONNECTED & AUTHORIZED")
        setupMediaSessionManager()
        scanActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { processNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val removedPkg = sbn?.packageName ?: ""
        if (removedPkg == activeController?.packageName) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            // Re-scan for any other music playing
            scanActiveNotifications()
        }
    }

    fun scanActiveNotifications() {
        try {
            val notifications = activeNotifications ?: emptyArray()
            Log.d(tag, "Scanning active notifications: found ${notifications.size}")

            for (sbn in notifications) {
                if (processNotification(sbn)) {
                    return
                }
            }

            // Fallback to active sessions manager query
            queryMediaSessions()
        } catch (e: Exception) {
            Log.e(tag, "Error scanning notifications: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification): Boolean {
        try {
            val notification = sbn.notification ?: return false
            val extras = notification.extras ?: return false
            val pkg = sbn.packageName.lowercase()

            // 1. Check for MediaSession Token directly attached to notification
            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }

            if (token != null) {
                Log.d(tag, "Found MediaSession token in notification from $pkg")
                attachToMediaSession(token)
                return true
            }

            // 2. Check if notification is from a known music app (Spotify, YouTube Music, etc.)
            val isMusic = pkg.contains("spotify") || 
                          pkg.contains("music") || 
                          pkg.contains("audio") || 
                          pkg.contains("youtube") || 
                          pkg.contains("deezer") || 
                          pkg.contains("tidal") || 
                          pkg.contains("soundcloud") ||
                          notification.category == Notification.CATEGORY_TRANSPORT

            if (isMusic) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
                val album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

                if (title.isNotEmpty()) {
                    var artworkBmp: Bitmap? = null
                    try {
                        val largeIcon = notification.getLargeIcon()
                        artworkBmp = drawableToBitmap(largeIcon?.loadDrawable(this))
                    } catch (e: Exception) {
                        Log.d(tag, "Could not load notification large icon: ${e.message}")
                    }

                    val track = MusicTrack(
                        title = title,
                        artist = if (artist.isNotEmpty()) artist else "Unknown Artist",
                        album = album,
                        duration = 240,
                        position = 30,
                        playing = true,
                        artwork = artworkBmp
                    )

                    Log.d(tag, "Extracted track directly from notification: $title by $artist")
                    currentTrack = track
                    onTrackChanged?.invoke(track)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing notification: ${e.message}")
        }
        return false
    }

    private fun attachToMediaSession(token: MediaSession.Token) {
        try {
            val controller = MediaController(this, token)
            if (activeController?.packageName != controller.packageName) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = controller
                controller.registerCallback(controllerCallback)
                Log.d(tag, "Attached to controller: ${controller.packageName}")
            }
            dispatchCurrentMetadata()
        } catch (e: Exception) {
            Log.e(tag, "Error attaching to MediaSession: ${e.message}")
        }
    }

    private fun setupMediaSessionManager() {
        try {
            if (mediaSessionManager == null) {
                mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val componentName = ComponentName(this, this::class.java)

                mediaSessionManager?.addOnActiveSessionsChangedListener({ controllers ->
                    Log.d(tag, "Active media sessions changed: ${controllers?.size ?: 0} sessions")
                    updateActiveController(controllers)
                }, componentName)
            }
            queryMediaSessions()
        } catch (e: SecurityException) {
            Log.e(tag, "Notification access permission not granted yet: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error in setupMediaSessionManager: ${e.message}")
        }
    }

    private fun queryMediaSessions() {
        try {
            val componentName = ComponentName(this, this::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.e(tag, "Error querying active sessions: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            return
        }

        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()

        if (activeController?.packageName != playingController.packageName) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = playingController
            activeController?.registerCallback(controllerCallback)
            Log.d(tag, "Active session attached to: ${activeController?.packageName}")
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
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val positionMs = state?.position ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        // Extract artwork bitmap
        var artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (artworkBitmap == null) {
            val artUriString = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (!artUriString.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(artUriString)
                    val input = contentResolver.openInputStream(uri)
                    artworkBitmap = BitmapFactory.decodeStream(input)
                    input?.close()
                } catch (e: Exception) {
                    Log.d(tag, "Could not load art URI: ${e.message}")
                }
            }
        }

        val track = MusicTrack(
            title = title,
            artist = artist,
            album = album,
            duration = if (durationMs > 0) durationMs / 1000 else 240,
            position = (positionMs / 1000).coerceAtLeast(0),
            playing = isPlaying,
            artwork = artworkBitmap
        )

        Log.d(tag, "Broadcasting media session track: $title by $artist (playing: $isPlaying)")
        currentTrack = track
        onTrackChanged?.invoke(track)
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        activeController?.unregisterCallback(controllerCallback)
        super.onDestroy()
    }
}
