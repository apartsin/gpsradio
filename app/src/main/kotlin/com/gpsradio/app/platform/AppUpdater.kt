package com.gpsradio.app.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.gpsradio.app.BuildConfig
import com.gpsradio.app.GpsRadioApp
import com.gpsradio.app.R
import com.gpsradio.core.update.UpdateClient
import com.gpsradio.core.update.UpdateInfo
import com.gpsradio.core.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the update UI shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Android asks the listener to allow "Install unknown apps" for GPS Radio first. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Self-update for an app that is not distributed through a store (spec B §36): checks the manifest
 * CI publishes with the tested build, downloads the APK (SHA-256 verified) and hands it to Android's
 * PackageInstaller. Android itself refuses an APK signed with a different key.
 */
class AppUpdater(
    private val context: Context,
    private val client: UpdateClient?,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val enabled: Boolean get() = client != null

    /**
     * Checks now ([manual], or [onStart]: every app start, spec B §36) or when the last automatic check is old enough.
     * Only a manual check reports "checking" and failures; the others stay silent.
     */
    fun check(manual: Boolean, onStart: Boolean = false) {
        val c = client ?: return
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && !onStart && !Updates.autoCheckDue(prefs.getLong(KEY_LAST_CHECK, 0), now)) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            try {
                val info = c.latest()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                _state.value = if (info != null && Updates.isNewer(currentVersion, info)) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Automatic checks fail silently (offline, GitHub down); manual ones say why.
                _state.value = if (manual) UpdateState.Failed(context.getString(R.string.update_check_failed, e.message.toString())) else UpdateState.Idle
            }
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Intent to the system page where the listener allows installs from GPS Radio. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads and installs [info]; asks for the install permission first when needed. */
    fun install(info: UpdateInfo) {
        val c = client ?: return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val apk = File(context.cacheDir, "updates/gpsradio-${info.version}.apk")
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                c.download(info, apk) { done, total ->
                    if (total > 0) _state.value = UpdateState.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                }
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) { commit(apk) }
                // Android normally answers within seconds (a confirmation or the result). If it never does, don't
                // leave the listener on "Installing…" forever: offer to try again.
                scope.launch {
                    kotlinx.coroutines.delay(INSTALL_TIMEOUT_MS)
                    if (_state.value == UpdateState.Installing(info) && _confirm.value == null) {
                        _state.value = UpdateState.Failed(context.getString(R.string.update_not_installed), info)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: context.getString(R.string.update_failed), info)
            }
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: once GPS Radio installed itself, later updates need no confirmation tap.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("gpsradio.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(callback.intentSender)
        }
    }

    /**
     * Android's "install this update?" screen, waiting to be shown. A broadcast receiver may not open it (Android
     * blocks activity starts from the background), so the visible activity collects this and opens it at once;
     * a notification is the fallback when the app isn't on screen.
     */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    internal fun requestConfirm(intent: Intent) {
        _confirm.value = intent
        notifyConfirm(intent)
    }

    /** Opens a waiting install confirmation from the foreground activity. */
    fun showPendingConfirm(activity: android.app.Activity) {
        val confirm = _confirm.value ?: return
        _confirm.value = null
        if (state.value !is UpdateState.Installing) return
        runCatching { activity.startActivity(confirm) }
            .onSuccess { cancelConfirmNotification() }
            .onFailure { _state.value = UpdateState.Failed(context.getString(R.string.update_not_installed), (state.value as? UpdateState.Installing)?.info) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyConfirm(intent: Intent) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(UPDATE_CHANNEL, context.getString(R.string.update_channel), android.app.NotificationManager.IMPORTANCE_HIGH),
        )
        val tap = PendingIntent.getActivity(context, 7, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val info = (state.value as? UpdateState.Installing)?.info
        val n = androidx.core.app.NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(context.getString(R.string.update_ready_title, info?.version.orEmpty()))
            .setContentText(context.getString(R.string.update_ready_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, n) }
    }

    private fun cancelConfirmNotification() {
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID) }
    }

    /** Result from PackageInstaller (on success the app is replaced and restarted, so this rarely runs). */
    internal fun onInstallResult(status: Int, message: String?) {
        _confirm.value = null
        cancelConfirmNotification()
        val info = (state.value as? UpdateState.Installing)?.info
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        context.getString(R.string.update_conflict)
                    PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.update_no_storage)
                    else -> message?.let { context.getString(R.string.update_not_installed_reason, it) } ?: context.getString(R.string.update_not_installed)
                },
                info,
            )
        }
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val UPDATE_CHANNEL = "updates"
        const val UPDATE_NOTIFICATION_ID = 7
    }
}

/** Receives PackageInstaller results; asks the listener to confirm when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            // Opened by the visible activity (it collects updater.confirm); a notification if the app isn't on screen.
            (context.applicationContext as? GpsRadioApp)?.updater?.requestConfirm(confirm)
            return
        }
        val app = context.applicationContext as? GpsRadioApp ?: return
        app.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/**
 * After an update is installed Android stops the old app; this says it's done and offers to reopen GPS Radio
 * (an app can't start its own activity from the background).
 */
class UpdatedReceiver : BroadcastReceiver() {
    @android.annotation.SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel("updates", context.getString(R.string.update_channel), android.app.NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 8,
            Intent(context, com.gpsradio.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(context.getString(R.string.update_done_title, BuildConfig.VERSION_NAME))
            .setContentText(context.getString(R.string.update_done_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(8, n) }
    }
}
