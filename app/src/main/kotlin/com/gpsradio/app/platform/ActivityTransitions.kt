package com.gpsradio.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gpsradio.app.GpsRadioApp
import com.gpsradio.core.model.ActivityType
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Activity Recognition Transition API (spec B §24): entering IN_VEHICLE, ON_BICYCLE, WALKING,
 * RUNNING or STILL is forwarded to the session as a prior for travel-mode detection.
 * Everything is best effort: without the permission or Play services, mode detection
 * simply stays speed-based.
 */
object ActivityTransitions {
    private const val ACTION = "com.gpsradio.app.ACTIVITY_TRANSITION"
    private const val REQUEST_CODE = 42

    private val tracked = listOf(
        DetectedActivity.IN_VEHICLE,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.STILL,
    )

    fun toActivityType(detected: Int): ActivityType? = when (detected) {
        DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
        DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityType.WALKING
        DetectedActivity.RUNNING -> ActivityType.RUNNING
        DetectedActivity.STILL -> ActivityType.STILL
        else -> null
    }

    fun permissionGranted(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION)
        // Play services fills in the transition result, so the intent must be mutable on Android 12+.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** Starts transition updates; returns false when the permission is missing or the request failed. */
    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (!permissionGranted(context)) return false
        val transitions = tracked.map { type ->
            ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        return runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        if (!permissionGranted(context)) return
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context)) }
    }
}

/** Receives activity transitions and hands the latest entered activity to the radio session. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val entered = result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .maxByOrNull { it.elapsedRealTimeNanos }
            ?: return
        val type = ActivityTransitions.toActivityType(entered.activityType) ?: return
        val app = context.applicationContext as? GpsRadioApp ?: return
        app.session.onActivity(type)
    }
}
