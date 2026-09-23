package com.gpsradio.app.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.session.AreaLabeler
import com.gpsradio.core.favorites.FavoritesStore
import com.gpsradio.core.journal.JournalStore
import com.gpsradio.core.memory.MemoryStore
import com.gpsradio.core.session.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class FileHistoryStore(context: Context) : HistoryStore {
    private val file = File(context.filesDir, "heard_history.json")
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(serialized: String) = file.writeText(serialized)
}

/** Learned listener preferences; stays on the device (excluded from backups). */
class FileMemoryStore(context: Context) : MemoryStore {
    private val file = File(context.filesDir, "user_memory.json")
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(serialized: String) = file.writeText(serialized)
}

/** Starred places; stays on the device (excluded from backups). */
class FileFavoritesStore(context: Context) : FavoritesStore {
    private val file = File(context.filesDir, "favorites.json")
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(serialized: String) = file.writeText(serialized)
}

/** Trip journal of heard stories; stays on the device (excluded from backups). */
class FileJournalStore(context: Context) : JournalStore {
    private val file = File(context.filesDir, "journal.json")
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(serialized: String) = file.writeText(serialized)
}

/** On-device reverse geocoding to a coarse city/region/country label for localized web search. */
class GeocoderAreaLabeler(private val context: Context) : AreaLabeler {
    override suspend fun label(point: GeoPoint): AreaLabel? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        val address: Address? = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(point.lat, point.lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(point.lat, point.lon, 1)?.firstOrNull() }.getOrNull()
        }
        address ?: return null
        return AreaLabel(
            city = address.locality ?: address.subAdminArea,
            region = address.adminArea,
            countryCode = address.countryCode,
        )
    }
}
