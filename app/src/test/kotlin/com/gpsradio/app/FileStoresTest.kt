package com.gpsradio.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.platform.FileHistoryStore
import com.gpsradio.app.platform.FileMemoryStore
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.memory.UserMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class FileStoresTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun memorySurvivesAcrossStoreInstances() {
        val store = FileMemoryStore(context)
        assertNull(store.load())
        val m = UserMemory().apply { remember(MemoryCategory.LIKE, "Waterfalls", null, 1) }
        store.save(m.serialize())
        val restored = UserMemory().apply { restore(FileMemoryStore(context).load()) }
        assertEquals(listOf("Waterfalls"), restored.all.map { it.text })
    }

    @Test
    fun historyStoreRoundTrips() {
        FileHistoryStore(context).save("{\"ids\":{}}")
        assertEquals("{\"ids\":{}}", FileHistoryStore(context).load())
    }
}
