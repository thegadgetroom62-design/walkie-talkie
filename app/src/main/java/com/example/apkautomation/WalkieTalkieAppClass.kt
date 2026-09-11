package com.example.apkautomation

import android.app.Application
import android.util.Log

/**
 * Global Application class with uncaught exception protection for HMS/EMUI and diverse Android ROMs.
 */
class WalkieTalkieAppClass : Application() {

    companion object {
        private const val TAG = "WalkieTalkieApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Guard against unhandled crashes on custom ROMs / Huawei HMS devices
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception caught on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
