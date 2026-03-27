package com.echostack.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import java.util.UUID

/**
 * Manages device ID (EncryptedSharedPreferences) and fingerprint collection.
 */
class DeviceManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "echostack_sdk_prefs"
        private const val KEY_DEVICE_ID = "echostack_device_id"
    }

    val echoStackId: String

    /**
     * Google Advertising ID, set externally by [AdvertisingIdManager] after async fetch.
     * Included in fingerprint when non-null.
     */
    var gaid: String? = null

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        echoStackId = loadOrCreateDeviceId()
    }

    /**
     * Collect device fingerprint for the install ping.
     */
    fun collectFingerprint(): Map<String, Any> {
        val fingerprint = mutableMapOf<String, Any>()

        fingerprint["user_agent"] = buildUserAgent()
        fingerprint["device_model"] = Build.MODEL
        fingerprint["os_version"] = Build.VERSION.RELEASE

        // Screen resolution
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            fingerprint["screen_resolution"] = "${metrics.widthPixels}x${metrics.heightPixels}"
        }

        fingerprint["language"] = Locale.getDefault().toString()

        // Include GAID when available (user has not opted out of ad tracking)
        gaid?.let { fingerprint["gaid"] = it }

        return fingerprint
    }

    private fun buildUserAgent(): String {
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "App"
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        return "$appName/$appVersion (${Build.MODEL}; Android ${Build.VERSION.RELEASE}) EchoStackSDK/1.0"
    }

    private fun loadOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}
