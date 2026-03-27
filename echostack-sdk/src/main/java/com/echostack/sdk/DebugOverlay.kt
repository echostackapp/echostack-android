package com.echostack.sdk

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Lightweight floating debug overlay for development.
 * Shows SDK status, device ID, last event, match type, and attribution network.
 * Only functional when [LogLevel.DEBUG] is active.
 */
internal class DebugOverlay(private val activity: Activity) {

    private var overlayView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    /** Last event type sent through the SDK, updated externally. */
    var lastEventSent: String? = null

    fun show() {
        if (overlayView != null) return

        val textView = TextView(activity).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.GREEN)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(16, 12, 16, 12)
            text = buildStatusText()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        activity.windowManager.addView(textView, params)
        overlayView = textView

        startPeriodicUpdate()
    }

    fun hide() {
        stopPeriodicUpdate()
        overlayView?.let { view ->
            try {
                activity.windowManager.removeView(view)
            } catch (_: Exception) {
                // View may already be detached
            }
        }
        overlayView = null
    }

    fun refresh() {
        handler.post {
            overlayView?.text = buildStatusText()
        }
    }

    private fun buildStatusText(): String {
        val configured = EchoStack.isConfiguredForDebug()
        val deviceId = EchoStack.getEchoStackId()
        val truncatedId = deviceId?.take(8)?.plus("...") ?: "n/a"
        val attribution = EchoStack.getAttributionParams()
        val matchType = attribution?.get("match_type")?.toString() ?: "none"
        val network = attribution?.get("network")?.toString() ?: "none"
        val lastEvent = lastEventSent ?: "none"
        val disabled = EchoStack.isSdkDisabled()

        return buildString {
            appendLine("--- EchoStack Debug ---")
            appendLine("State: ${if (disabled) "DISABLED" else if (configured) "OK" else "NOT CONFIGURED"}")
            appendLine("Device: $truncatedId")
            appendLine("Last event: $lastEvent")
            appendLine("Match: $matchType")
            appendLine("Network: $network")
        }.trimEnd()
    }

    private fun startPeriodicUpdate() {
        val runnable = object : Runnable {
            override fun run() {
                overlayView?.text = buildStatusText()
                handler.postDelayed(this, 2000L)
            }
        }
        updateRunnable = runnable
        handler.postDelayed(runnable, 2000L)
    }

    private fun stopPeriodicUpdate() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }
}
