package com.gpsradio.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.session.RadioUiState
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gpsradio.app.GpsRadioApp
import com.gpsradio.app.R
import com.gpsradio.app.ui.MainActivity
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.TravelMode
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the radio alive with the screen off: owns location updates
 * (adapted to travel mode for battery) and the playback notification.
 */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var fused: FusedLocationProviderClient
    private var currentMode: TravelMode? = null
    private var modeWatcher: kotlinx.coroutines.Job? = null
    private var stateWatcher: kotlinx.coroutines.Job? = null
    private lateinit var mediaSession: MediaSessionCompat

    private val session get() = (application as GpsRadioApp).session

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { l ->
                session.onLocation(
                    LocationSample(
                        lat = l.latitude,
                        lon = l.longitude,
                        accuracyM = if (l.hasAccuracy()) l.accuracy else 50f,
                        timestampMs = l.time,
                        speedMps = if (l.hasSpeed()) l.speed else null,
                        bearingDeg = if (l.hasBearing()) l.bearing else null,
                        provider = l.provider ?: "fused",
                    ),
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        // Lock screen, headset buttons and car Bluetooth controls.
        mediaSession = MediaSessionCompat(this, "GpsRadio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { session.resume() }
                override fun onPause() { session.pause() }
                override fun onSkipToNext() { session.skip() }
                override fun onStop() { stopRadio() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRadio()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { session.pause(); return START_STICKY }
            ACTION_RESUME -> { session.resume(); return START_STICKY }
            ACTION_SKIP -> { session.skip(); return START_STICKY }
        }
        // The microphone type lets the live voice hear answers hands-free while the screen is off.
        val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or mic
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(session.state.value), type)
        } catch (e: Exception) {
            // Android 14+: a sticky restart after location permission was revoked cannot use the
            // location type. Stop cleanly instead of crashing; the user restarts from the app.
            session.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        session.start()
        requestUpdates(currentMode ?: TravelMode.UNKNOWN)
        seedLastKnownLocation()
        if (stateWatcher == null) stateWatcher = scope.launch {
            session.state.map { Triple(it.radioState, it.nowPlaying?.title ?: it.focus?.name, it.area?.city) }
                .distinctUntilChanged()
                .collect { updateMediaUi(session.state.value) }
        }
        if (modeWatcher == null) modeWatcher = scope.launch {
            session.state.map { it.location?.travelMode ?: TravelMode.UNKNOWN }
                .distinctUntilChanged()
                .collect { requestUpdates(it) }
        }
        return START_STICKY
    }

    /** Start from the last known fix so discovery can begin before the first fresh GPS update. */
    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation() {
        runCatching {
            fused.lastLocation.addOnSuccessListener { l ->
                if (l != null) callback.onLocationResult(LocationResult.create(listOf(l)))
            }
        }
    }

    /** Faster, high-accuracy fixes while moving; coarser and slower when stationary. */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(mode: TravelMode) {
        if (mode == currentMode) return
        currentMode = mode
        val request = when (mode) {
            TravelMode.STATIONARY -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
                .setMinUpdateDistanceMeters(25f)
                .build()
            TravelMode.DRIVING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000)
                .setMinUpdateDistanceMeters(30f)
                .build()
            // 10 s / 15 m is plenty for a ~400 m walking proximity scale, and kinder to the battery.
            else -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
                .setMinUpdateDistanceMeters(15f)
                .build()
        }
        runCatching {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
    }

    private fun stopRadio() {
        session.stop()
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun updateMediaUi(s: RadioUiState) {
        val playing = s.radioState != RadioState.PAUSED && s.radioState != RadioState.IDLE
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0L, 1f)
                .build(),
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title(s))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.area?.city ?: getString(R.string.app_name))
                .build(),
        )
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(s)) }
    }

    private fun title(s: RadioUiState): String = s.nowPlaying?.title?.takeIf { s.radioState == RadioState.NARRATING }
        ?: when (s.radioState) {
            RadioState.PAUSED -> "Paused"
            RadioState.CONVERSING -> "Talking with you"
            RadioState.RESEARCHING -> "Tuning in…"
            else -> getString(R.string.notification_text)
        }

    override fun onDestroy() {
        runCatching { mediaSession.release() }
        runCatching { fused.removeLocationUpdates(callback) }
        scope.cancel()
        session.stop()
        super.onDestroy()
    }

    private fun buildNotification(s: RadioUiState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RadioService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = s.radioState == RadioState.PAUSED
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title(s))
            .setContentText(s.area?.city?.let { "$it · tap to open" } ?: getString(R.string.app_name))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 2),
            )
            .addAction(android.R.drawable.ic_media_next, "Skip", action(ACTION_SKIP, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), action(ACTION_STOP, 1))
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.gpsradio.app.STOP"
        private const val ACTION_PAUSE = "com.gpsradio.app.PAUSE"
        private const val ACTION_RESUME = "com.gpsradio.app.RESUME"
        private const val ACTION_SKIP = "com.gpsradio.app.SKIP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
    }
}
