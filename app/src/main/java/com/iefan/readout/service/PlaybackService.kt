package com.iefan.readout.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.iefan.readout.MainActivity
import com.iefan.readout.tts.AuraTtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class PlaybackService : Service() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updatesJob: Job? = null

    companion object {
        const val CHANNEL_ID = "readout_playback_channel"
        const val NOTIFICATION_ID = 2024
        
        const val ACTION_START = "com.iefan.readout.ACTION_START"
        const val ACTION_PLAY_PAUSE = "com.iefan.readout.ACTION_PLAY_PAUSE"
        const val ACTION_SKIP_FORWARD = "com.iefan.readout.ACTION_SKIP_FORWARD"
        const val ACTION_SKIP_BACKWARD = "com.iefan.readout.ACTION_SKIP_BACKWARD"
        const val ACTION_STOP = "com.iefan.readout.ACTION_STOP"
        
        const val CHARS_PER_SECOND = 15L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("PlaybackService", "Service onCreate")
        
        mediaSession = MediaSession(this, "ReadoutPlaybackSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    AuraTtsEngine.instance?.startPlayback()
                }

                override fun onPause() {
                    AuraTtsEngine.instance?.pausePlayback()
                }

                override fun onSkipToNext() {
                    AuraTtsEngine.instance?.skipForward15s()
                }

                override fun onSkipToPrevious() {
                    AuraTtsEngine.instance?.skipBackward15s()
                }

                override fun onSeekTo(pos: Long) {
                    val charIndex = (pos * CHARS_PER_SECOND) / 1000L
                    AuraTtsEngine.instance?.seekToCharacter(charIndex.toInt())
                }
            })
            isActive = true
        }

        createNotificationChannel()
        startUpdatesObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("PlaybackService", "onStartCommand action: $action")
        
        when (action) {
            ACTION_START -> {
                startUpdatesObserver()
                updateNotification()
                updateMediaSessionState()
            }
            ACTION_PLAY_PAUSE -> {
                AuraTtsEngine.instance?.let { engine ->
                    if (engine.isPlaying.value) {
                        engine.pausePlayback()
                    } else {
                        engine.startPlayback()
                    }
                }
            }
            ACTION_SKIP_FORWARD -> {
                AuraTtsEngine.instance?.skipForward15s()
            }
            ACTION_SKIP_BACKWARD -> {
                AuraTtsEngine.instance?.skipBackward15s()
            }
            ACTION_STOP -> {
                AuraTtsEngine.instance?.stop()
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startUpdatesObserver() {
        updatesJob?.cancel()
        updatesJob = serviceScope.launch {
            val engine = AuraTtsEngine.instance
            if (engine != null) {
                launch {
                    engine.isPlaying.collect {
                        updateNotification()
                        updateMediaSessionState()
                    }
                }
                launch {
                    engine.currentSentenceIndex.collect {
                        updateNotification()
                        updateMediaSessionState()
                    }
                }
                launch {
                    engine.currentWordRange.collect {
                        updateMediaSessionState()
                    }
                }
            } else {
                Log.w("PlaybackService", "AuraTtsEngine.instance is null during updates observation. Stopping service.")
                stopForegroundService()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Audio Playback"
            val descriptionText = "Controls for current audio reading"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val engine = AuraTtsEngine.instance ?: return
        val isPlaying = engine.isPlaying.value
        val title = engine.documentTitle.ifBlank { "Readout Player" }

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playPauseAction = Notification.Action.Builder(
            playPauseIcon, "Play/Pause",
            getPendingIntent(ACTION_PLAY_PAUSE)
        ).build()

        val skipBackwardAction = Notification.Action.Builder(
            android.R.drawable.ic_media_rew, "Rewind",
            getPendingIntent(ACTION_SKIP_BACKWARD)
        ).build()

        val skipForwardAction = Notification.Action.Builder(
            android.R.drawable.ic_media_ff, "Forward",
            getPendingIntent(ACTION_SKIP_FORWARD)
        ).build()

        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop",
            getPendingIntent(ACTION_STOP)
        ).build()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(skipBackwardAction) // 0
            .addAction(playPauseAction)    // 1
            .addAction(skipForwardAction)   // 2
            .addAction(stopAction)          // 3

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMediaSessionState() {
        val engine = AuraTtsEngine.instance ?: return
        val isPlaying = engine.isPlaying.value
        val position = if (engine.totalCharacters > 0) (engine.currentCharacterIndex.toLong() * 1000L) / CHARS_PER_SECOND else 0L
        val duration = if (engine.totalCharacters > 0) (engine.totalCharacters.toLong() * 1000L) / CHARS_PER_SECOND else 0L

        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (isPlaying) engine.playbackSpeed.value else 0f
            )
        mediaSession?.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, engine.documentTitle.ifBlank { "Readout Player" })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun stopForegroundService() {
        Log.d("PlaybackService", "stopForegroundService")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PlaybackService", "Service onDestroy")
        updatesJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
    }
}
