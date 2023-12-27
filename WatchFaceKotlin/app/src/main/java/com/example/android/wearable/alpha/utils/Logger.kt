package com.example.android.wearable.alpha.utils

import android.util.Log
import com.example.android.wearable.alpha.BuildConfig

object Logger {
    private const val SHOULD_LOG = false

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG && SHOULD_LOG) {
            Log.d(tag, message)
        }
    }
}
