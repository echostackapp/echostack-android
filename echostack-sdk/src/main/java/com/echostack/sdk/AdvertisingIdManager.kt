package com.echostack.sdk

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages retrieval of Google Advertising ID (GAID).
 *
 * Respects user privacy: if Limit Ad Tracking is enabled, the GAID is discarded.
 * Handles Google Play Services unavailability gracefully — [gaid] will be null.
 */
class AdvertisingIdManager(private val context: Context) {

    /**
     * The Google Advertising ID, or null if unavailable or user opted out.
     */
    var gaid: String? = null
        private set

    /**
     * Fetch the GAID from Google Play Services on a background thread.
     * Must be called off the main thread (uses [Dispatchers.IO] internally).
     */
    suspend fun fetchGaid() {
        try {
            val adInfo = withContext(Dispatchers.IO) {
                AdvertisingIdClient.getAdvertisingIdInfo(context)
            }

            if (adInfo.isLimitAdTrackingEnabled) {
                Logger.debug("Limit Ad Tracking enabled — discarding GAID")
                gaid = null
                return
            }

            val id = adInfo.id
            if (id.isNullOrBlank()) {
                Logger.debug("GAID returned blank — ignoring")
                gaid = null
                return
            }

            gaid = id
            Logger.debug("GAID collected: ${id.take(8)}...")
        } catch (e: Exception) {
            // Google Play Services unavailable, not installed, or any other error.
            // Never crash the host app — just log and leave gaid as null.
            Logger.debug("GAID unavailable: ${e.message}")
            gaid = null
        }
    }
}
