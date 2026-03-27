package com.echostack.sdk

import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Meta's install referrer data from on-device ContentProviders.
 *
 * When a user views or clicks a Meta ad, the Facebook/Messenger app writes
 * encrypted campaign data to a ContentProvider on the device. Reading this
 * token enables deterministic view-through attribution even without a click
 * on a tracking link.
 *
 * The encrypted token is sent to the backend, which decrypts it via Meta's
 * Graph API to extract campaign_id, adset_id, and ad_id.
 *
 * Two ContentProvider authorities are tried (in order):
 * 1. Facebook app: `com.facebook.katana.provider.AttributionIdProvider`
 * 2. Messenger app: `com.facebook.orca.provider.AttributionIdProvider`
 *
 * If neither app is installed, or the provider returns no data, [metaInstallReferrer]
 * remains null and the SDK proceeds without it.
 */
class MetaInstallReferrerManager(private val context: Context) {

    companion object {
        private val ATTRIBUTION_URIS = listOf(
            "content://com.facebook.katana.provider.AttributionIdProvider/attribution",
            "content://com.facebook.orca.provider.AttributionIdProvider/attribution",
        )
    }

    /**
     * The Meta install referrer token (encrypted), or null if unavailable.
     *
     * This value is populated by [fetchMetaReferrer] and should be included
     * in the install ping payload when non-null.
     */
    var metaInstallReferrer: String? = null
        private set

    /**
     * Attempt to read Meta's attribution token from on-device ContentProviders.
     *
     * Tries Facebook app first, then Messenger. Returns on the first successful
     * read. All exceptions are caught — never crashes the host app.
     *
     * Must be called from a coroutine (uses [Dispatchers.IO]).
     */
    suspend fun fetchMetaReferrer() {
        try {
            val token = withContext(Dispatchers.IO) {
                readAttributionToken()
            }

            if (token.isNullOrBlank()) {
                Logger.debug("Meta install referrer not available")
                metaInstallReferrer = null
                return
            }

            metaInstallReferrer = token
            Logger.debug("Meta install referrer collected: ${token.take(16)}...")
        } catch (e: Exception) {
            // ContentProvider may not exist if Facebook/Messenger is not installed,
            // or the user has not interacted with a Meta ad. Never crash the host app.
            Logger.debug("Meta install referrer unavailable: ${e.message}")
            metaInstallReferrer = null
        }
    }

    /**
     * Read the attribution token from Meta's ContentProviders.
     *
     * Tries each URI in order. The ContentProvider exposes a single column
     * containing the attribution ID / encrypted token string.
     *
     * @return The attribution token string, or null if not available.
     */
    private fun readAttributionToken(): String? {
        for (uriString in ATTRIBUTION_URIS) {
            try {
                val uri = Uri.parse(uriString)
                val cursor: Cursor? = context.contentResolver.query(
                    uri,
                    arrayOf("aid"),  // attribution ID column
                    null,
                    null,
                    null,
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val token = it.getString(0)
                        if (!token.isNullOrBlank()) {
                            return token
                        }
                    }
                }
            } catch (_: Exception) {
                // This ContentProvider authority is not available — try the next one.
                // SecurityException, IllegalArgumentException, etc. are all expected
                // when the Facebook/Messenger app is not installed.
            }
        }
        return null
    }
}
