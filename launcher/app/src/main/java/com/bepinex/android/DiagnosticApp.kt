package com.bepinex.android

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * Temporary diagnostic: log the framework's normal application-creation stack
 * so the PairIP compatibility layer can reproduce it exactly.
 */
class DiagnosticApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Log.i("BepInExDiag", "attachBaseContext stack:\n" + Throwable().stackTrace.joinToString("\n"))
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("BepInExDiag", "onCreate stack:\n" + Throwable().stackTrace.joinToString("\n"))
    }
}
