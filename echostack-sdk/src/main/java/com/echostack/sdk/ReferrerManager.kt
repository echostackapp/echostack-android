package com.echostack.sdk

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Represents a click ID extracted from the install referrer.
 *
 * @property value The raw click ID string.
 * @property type One of "fbclid", "gclid", or "ttclid".
 */
data class ClickId(val value: String, val type: String)

/**
 * Manages Google Play Install Referrer API for deterministic matching.
 * Extracts click IDs (fbclid, gclid, ttclid) and UTM parameters from the referrer URL.
 */
class ReferrerManager(private val context: Context) {

    var referrerData: Map<String, String>? = null
        private set

    var gclid: String? = null
        private set

    var fbclid: String? = null
        private set

    var ttclid: String? = null
        private set

    /**
     * All click IDs found in the install referrer, keyed by type.
     * Possible keys: "fbclid", "gclid", "ttclid".
     */
    var clickIds: Map<String, ClickId> = emptyMap()
        private set

    /**
     * Fetch install referrer from Google Play. Times out after 5 seconds.
     */
    suspend fun fetchInstallReferrer() {
        val result = withTimeoutOrNull(5000L) {
            fetchReferrerInternal()
        }

        if (result != null) {
            referrerData = result
            gclid = result["gclid"]
            fbclid = result["fbclid"]
            ttclid = result["ttclid"]

            val found = mutableMapOf<String, ClickId>()
            gclid?.let { found["gclid"] = ClickId(it, "gclid") }
            fbclid?.let { found["fbclid"] = ClickId(it, "fbclid") }
            ttclid?.let { found["ttclid"] = ClickId(it, "ttclid") }
            clickIds = found

            Logger.debug("Install referrer: $result")
            if (found.isNotEmpty()) {
                Logger.debug("Click IDs found: ${found.keys.joinToString()}")
            }
        } else {
            Logger.debug("Install referrer not available or timed out")
        }
    }

    private suspend fun fetchReferrerInternal(): Map<String, String>? =
        suspendCancellableCoroutine { continuation ->
            val client = InstallReferrerClient.newBuilder(context).build()

            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try {
                            val details = client.installReferrer
                            val referrerUrl = details.installReferrer
                            val params = parseReferrer(referrerUrl)
                            continuation.resume(params)
                        } catch (e: Exception) {
                            Logger.error("Failed to read install referrer: ${e.message}")
                            continuation.resume(null)
                        } finally {
                            client.endConnection()
                        }
                    } else {
                        Logger.debug("Install referrer not available: code $responseCode")
                        continuation.resume(null)
                        client.endConnection()
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })

            continuation.invokeOnCancellation {
                try { client.endConnection() } catch (_: Exception) {}
            }
        }

    /**
     * Parse referrer URL into key-value pairs. Extracts click IDs
     * (fbclid, gclid, ttclid) and UTM parameters if present.
     */
    private fun parseReferrer(referrer: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        try {
            // Referrer string may be URL-encoded: utm_source=google&gclid=abc
            val uri = Uri.parse("https://dummy.com?$referrer")
            for (key in uri.queryParameterNames) {
                uri.getQueryParameter(key)?.let { params[key] = it }
            }
        } catch (e: Exception) {
            params["raw_referrer"] = referrer
        }
        return params
    }
}
