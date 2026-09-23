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
import androidx.core.app.NotificationCompat
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        session.start()
        requestUpdates(currentMode ?: TravelMode.UNKNOWN)
        if (modeWatcher == null) modeWatcher = scope.launch {
            session.state.map { it.location?.travelMode ?: TravelMode.UNKNOWN }
                .distinctUntilChanged()
                .collect { requestUpdates(it) }
        }
        return START_STICKY
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
            else -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
                .setMinUpdateDistanceMeters(8f)
                .build()
        }
        runCatching {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
        scope.cancel()
        session.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.gpsradio.app.STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
    }
}
