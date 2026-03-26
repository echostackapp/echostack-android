package com.echostack.sdk

import android.util.Log

/**
 * Internal logger with configurable level. No output in production by default.
 */
object Logger {

    private const val TAG = "EchoStack"
    var level: LogLevel = LogLevel.NONE

    fun debug(message: String) {
        if (level.value >= LogLevel.DEBUG.value) {
            Log.d(TAG, message)
        }
    }

    fun warning(message: String) {
        if (level.value >= LogLevel.WARNING.value) {
            Log.w(TAG, message)
        }
    }

    fun error(message: String) {
        if (level.value >= LogLevel.ERROR.value) {
            Log.e(TAG, message)
        }
    }
}
