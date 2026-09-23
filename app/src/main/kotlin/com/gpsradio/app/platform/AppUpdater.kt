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

    /** Checks now ([manual]) or when the last automatic check is old enough. */
    fun check(manual: Boolean) {
        val c = client ?: return
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && !Updates.autoCheckDue(prefs.getLong(KEY_LAST_CHECK, 0), now)) return
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
                _state.value = if (manual) UpdateState.Failed("Couldn't check for updates: ${e.message}") else UpdateState.Idle
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Update failed", info)
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

    /** Result from PackageInstaller (on success the app is replaced and restarted, so this rarely runs). */
    internal fun onInstallResult(status: Int, message: String?) {
        val info = (state.value as? UpdateState.Installing)?.info
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update can't replace the installed app (different signature). Install it from the download link instead."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update."
                    else -> "Update not installed" + (message?.let { ": $it" } ?: ".")
                },
                info,
            )
        }
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
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
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val app = context.applicationContext as? GpsRadioApp ?: return
        app.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
