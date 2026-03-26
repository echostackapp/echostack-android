package com.echostack.sdk

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Queued event waiting to be sent.
 */
data class QueuedEvent(
    val eventType: String,
    val parameters: Map<String, Any>,
    val eventAt: Long  // epoch millis
)

/**
 * Local event queue with batched flush.
 * Events stored in SharedPreferences. Flush every 30s, on significant events, on app pause.
 */
class EventQueue(
    private val networkClient: NetworkClient,
    private val deviceManager: DeviceManager,
    context: Context
) {

    companion object {
        private const val PREFS_NAME = "echostack_event_queue"
        private const val KEY_QUEUE = "queued_events"
        private const val MAX_QUEUE_SIZE = 1000
        private const val FLUSH_INTERVAL_MS = 30_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val queue = CopyOnWriteArrayList<QueuedEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        loadQueue()
        startFlushTimer()
    }

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: QueuedEvent) {
        queue.add(event)

        // Drop oldest if exceeded max
        while (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
            Logger.warning("Event queue full. Dropped oldest event.")
        }

        persistQueue()
    }

    /**
     * Flush queued events to server.
     */
    suspend fun flush() {
        if (queue.isEmpty()) return

        val eventsToSend = ArrayList(queue)
        val eventsArray = JSONArray()

        for (event in eventsToSend) {
            val eventJson = JSONObject().apply {
                put("event_type", event.eventType)
                put("parameters", JSONObject(event.parameters))
                put("event_at", dateFormat.format(Date(event.eventAt)))
            }
            eventsArray.put(eventJson)
        }

        val payload = JSONObject().apply {
            put("echostack_id", deviceManager.echoStackId)
            put("events", eventsArray)
        }

        Logger.debug("Flushing ${eventsToSend.size} events...")

        val response = networkClient.sendEvents(payload)

        if (response != null) {
            // Remove sent events
            queue.removeAll(eventsToSend.toSet())
            persistQueue()
            Logger.debug("Flushed ${eventsToSend.size} events successfully")
        } else {
            Logger.warning("Event flush failed — will retry on next cycle")
        }
    }

    private fun startFlushTimer() {
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    private fun persistQueue() {
        val array = JSONArray()
        for (event in queue) {
            val obj = JSONObject().apply {
                put("eventType", event.eventType)
                put("parameters", JSONObject(event.parameters))
                put("eventAt", event.eventAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    private fun loadQueue() {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val params = mutableMapOf<String, Any>()
                val paramsJson = obj.optJSONObject("parameters")
                if (paramsJson != null) {
                    for (key in paramsJson.keys()) {
                        params[key] = paramsJson.get(key)
                    }
                }
                queue.add(
                    QueuedEvent(
                        eventType = obj.getString("eventType"),
                        parameters = params,
                        eventAt = obj.getLong("eventAt")
                    )
                )
            }
        } catch (e: Exception) {
            Logger.error("Failed to load event queue: ${e.message}")
        }
    }
}
