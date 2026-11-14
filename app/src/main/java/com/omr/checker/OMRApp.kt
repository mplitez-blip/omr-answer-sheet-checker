package com.omr.checker

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class OMRApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initLocal()) {
            Log.d(TAG, "OpenCV initialized successfully via initLocal()")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
        }
    }

    companion object {
        private const val TAG = "OMRApp"
    }
}
