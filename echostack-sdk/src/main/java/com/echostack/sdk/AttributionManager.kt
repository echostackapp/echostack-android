package com.echostack.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Handles install ping, caches attribution result.
 */
class AttributionManager(
    private val deviceManager: DeviceManager,
    private val networkClient: NetworkClient,
    private val referrerManager: ReferrerManager,
    private val advertisingIdManager: AdvertisingIdManager,
    context: Context
) {

    companion object {
        private const val PREFS_NAME = "echostack_attribution"
        private const val KEY_ATTRIBUTION = "cached_attribution"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var cachedAttribution: Map<String, Any>? = null
        private set

    init {
        // Load cached attribution
        val cached = prefs.getString(KEY_ATTRIBUTION, null)
        if (cached != null) {
            try {
                cachedAttribution = jsonToMap(JSONObject(cached))
            } catch (_: Exception) {}
        }
    }

    /**
     * Send install ping to server. Called on every cold start; server handles dedup.
     */
    suspend fun sendInstallPing() {
        val fingerprint = deviceManager.collectFingerprint()
        val fpJson = JSONObject(fingerprint)

        val payload = JSONObject().apply {
            put("echostack_id", deviceManager.echoStackId)
            put("fingerprint", fpJson)
        }

        // Include GAID when available (user has not opted out)
        advertisingIdManager.gaid?.let { gaid ->
            payload.put("gaid", gaid)
            fpJson.put("gaid", gaid)
        }

        // Include click ID from Install Referrer if available.
        // Priority order: fbclid > gclid > ttclid (Meta is most common).
        val selectedClickId = referrerManager.fbclid?.let { ClickId(it, "fbclid") }
            ?: referrerManager.gclid?.let { ClickId(it, "gclid") }
            ?: referrerManager.ttclid?.let { ClickId(it, "ttclid") }

        selectedClickId?.let {
            payload.put("click_id", it.value)
            payload.put("click_id_type", it.type)
        }

        // Include raw referrer data
        referrerManager.referrerData?.let { data ->
            data["install_referrer"]?.let { fpJson.put("install_referrer", it) }
        }

        Logger.debug("Sending install ping...")

        val response = networkClient.sendInstallPing(payload) ?: run {
            Logger.warning("Install ping failed — will retry on next cold start")
            return
        }

        // Cache attribution
        if (response.has("attribution") && !response.isNull("attribution")) {
            val attribution = response.getJSONObject("attribution")
            cachedAttribution = jsonToMap(attribution)
            prefs.edit().putString(KEY_ATTRIBUTION, attribution.toString()).apply()
            Logger.debug("Attribution received: ${attribution.optString("match_type", "unknown")}")
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            if (value != JSONObject.NULL) {
                map[key] = value
            }
        }
        return map
    }
}
