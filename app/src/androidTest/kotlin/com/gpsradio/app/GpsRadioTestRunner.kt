package com.gpsradio.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs instrumentation tests against [TestGpsRadioApp] (fake servers, silent audio). */
class GpsRadioTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestGpsRadioApp::class.java.name, context)
}
