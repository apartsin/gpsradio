package com.gpsradio.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.platform.LocalModelCatalog
import com.gpsradio.app.platform.LocalModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A downloaded model is found again by the next app version (after an update) and never fetched twice. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class LocalModelsStorageTest {
    @Test
    fun modelKeptInNoBackupStorageIsReusedAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val spec = LocalModelCatalog.all.first()
        val file = File(File(context.noBackupFilesDir, "models"), spec.fileName)
        file.parentFile!!.mkdirs()
        file.writeText("model")

        // A fresh instance, as after the app was updated and restarted.
        val models = LocalModels(context, OkHttpClient(), CoroutineScope(Job()))
        assertEquals(setOf(spec.id), models.installed.value)
        assertTrue(models.downloads.value.isEmpty())
        assertEquals(file, models.fileOf(spec))

        models.delete(spec.id)
        assertFalse(file.exists())
        assertTrue(models.installed.value.isEmpty())
    }
}
