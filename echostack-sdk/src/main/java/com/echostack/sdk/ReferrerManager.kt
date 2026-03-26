package com.echostack.sdk

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages Google Play Install Referrer API for deterministic matching.
 * Extracts gclid, utm_source, utm_campaign from the referrer URL.
 */
class ReferrerManager(private val context: Context) {

    var referrerData: Map<String, String>? = null
        private set

    var gclid: String? = null
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
            Logger.debug("Install referrer: $result")
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
     * Parse referrer URL into key-value pairs. Extracts gclid if present.
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
