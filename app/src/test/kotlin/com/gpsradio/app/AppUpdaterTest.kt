package com.gpsradio.app

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.platform.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android's "install this update?" screen can't be opened from the install-result broadcast (background start),
 * so it waits for the visible activity, with a notification as the fallback (spec B §36).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppUpdaterTest {
    @Test
    fun anInstallConfirmationWaitsForTheActivityAndIsAlsoNotified() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val updater = AppUpdater(context, null)
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        updater.requestConfirm(confirm)
        assertEquals(confirm, updater.confirm.value)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }
}
