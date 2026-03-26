package com.echostack.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client using HttpURLConnection (zero external dependencies).
 * Handles auth headers, retry with exponential backoff, JSON serialization.
 */
class NetworkClient(private val configuration: Configuration) {

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS = longArrayOf(1000L, 3000L, 9000L)
        private const val TIMEOUT_MS = 15000
    }

    /**
     * Send install ping. Returns parsed JSON response or null on failure.
     */
    suspend fun sendInstallPing(payload: JSONObject): JSONObject? {
        return postJSON(configuration.installURL, payload)
    }

    /**
     * Send batched events. Returns parsed JSON response or null on failure.
     */
    suspend fun sendEvents(payload: JSONObject): JSONObject? {
        return postJSON(configuration.eventsURL, payload)
    }

    private suspend fun postJSON(urlString: String, payload: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection

                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("X-API-Key", configuration.apiKey)
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true

                    // Write body
                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }

                    val statusCode = connection.responseCode

                    // 401 = invalid API key → disable SDK
                    if (statusCode == 401) {
                        Logger.error("Invalid API key (401). Disabling SDK.")
                        EchoStack.disable()
                        return@withContext null
                    }

                    // Success
                    if (statusCode in 200..299) {
                        val body = readResponse(connection)
                        return@withContext JSONObject(body)
                    }

                    // 429 / 5xx → retry
                    if (statusCode == 429 || statusCode >= 500) {
                        Logger.warning("Server error $statusCode, attempt ${attempt + 1}/$MAX_RETRIES")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(RETRY_DELAYS[attempt])
                        }
                        continue
                    }

                    // 4xx → don't retry
                    Logger.error("Request failed with status $statusCode")
                    return@withContext null

                } catch (e: Exception) {
                    Logger.error("Network error: ${e.message}, attempt ${attempt + 1}/$MAX_RETRIES")
                    if (attempt < MAX_RETRIES - 1) {
                        delay(RETRY_DELAYS[attempt])
                    }
                }
            }

            Logger.error("All $MAX_RETRIES retry attempts exhausted")
            null
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readText()
        }
    }
}
