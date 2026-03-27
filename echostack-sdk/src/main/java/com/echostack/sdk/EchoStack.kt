package com.echostack.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.*

/**
 * EchoStack Android SDK — mobile attribution for ad networks.
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate() or Activity.onCreate():
 * EchoStack.configure(context, "es_live_...")
 *
 * // Send events:
 * EchoStack.sendEvent("purchase", mapOf("revenue" to 29.99, "currency" to "USD"))
 * ```
 */
object EchoStack {

    private var configuration: Configuration? = null
    private var deviceManager: DeviceManager? = null
    private var networkClient: NetworkClient? = null
    private var attributionManager: AttributionManager? = null
    private var eventQueue: EventQueue? = null
    private var referrerManager: ReferrerManager? = null
    private var advertisingIdManager: AdvertisingIdManager? = null
    private var metaReferrerManager: MetaInstallReferrerManager? = null

    private var isConfigured = false
    private var _isSdkDisabled = false
    private var debugOverlay: DebugOverlay? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize the SDK. Call once in Application.onCreate() or early in Activity.onCreate().
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        apiKey: String,
        serverURL: String = "https://api.echostack.app",
        logLevel: LogLevel = LogLevel.NONE
    ) {
        if (isConfigured) {
            Logger.warning("EchoStack already configured. Ignoring duplicate configure() call.")
            return
        }

        if (!apiKey.startsWith("es_live_") && !apiKey.startsWith("es_test_")) {
            Logger.error("Invalid API key format. Must start with 'es_live_' or 'es_test_'.")
            _isSdkDisabled = true
            return
        }

        Logger.level = logLevel
        Logger.debug("Configuring EchoStack SDK...")

        val appContext = context.applicationContext
        val config = Configuration(apiKey, serverURL, logLevel)
        configuration = config

        val device = DeviceManager(appContext)
        deviceManager = device

        val network = NetworkClient(config)
        networkClient = network

        val referrer = ReferrerManager(appContext)
        referrerManager = referrer

        val adIdManager = AdvertisingIdManager(appContext)
        advertisingIdManager = adIdManager

        val metaReferrer = MetaInstallReferrerManager(appContext)
        metaReferrerManager = metaReferrer

        val attribution = AttributionManager(device, network, referrer, adIdManager, metaReferrer, appContext)
        attributionManager = attribution

        val queue = EventQueue(network, device, appContext)
        eventQueue = queue

        isConfigured = true

        // Register lifecycle callbacks for automatic flush
        registerLifecycleCallbacks(appContext)

        // Fetch GAID, install referrer, and Meta referrer, then send install ping asynchronously
        scope.launch {
            adIdManager.fetchGaid()
            device.gaid = adIdManager.gaid
            referrer.fetchInstallReferrer()
            metaReferrer.fetchMetaReferrer()
            attribution.sendInstallPing()
        }

        Logger.debug("EchoStack SDK configured. Device ID: ${device.echoStackId}")
    }

    /**
     * Get the unique device installation ID.
     */
    @JvmStatic
    fun getEchoStackId(): String? = deviceManager?.echoStackId

    /**
     * Get attribution parameters after matching completes.
     */
    @JvmStatic
    fun getAttributionParams(): Map<String, Any>? = attributionManager?.cachedAttribution

    /**
     * Check if the SDK is disabled.
     */
    @JvmStatic
    fun isSdkDisabled(): Boolean = _isSdkDisabled

    /**
     * Send an in-app event. Events are queued and flushed in batches.
     */
    @JvmStatic
    @JvmOverloads
    fun sendEvent(eventType: String, parameters: Map<String, Any>? = null) {
        if (!isConfigured || _isSdkDisabled) {
            Logger.warning("SDK not configured or disabled. Event '$eventType' dropped.")
            return
        }

        val event = QueuedEvent(
            eventType = eventType,
            parameters = parameters ?: emptyMap(),
            eventAt = System.currentTimeMillis()
        )

        eventQueue?.enqueue(event)

        // Update debug overlay with last event
        debugOverlay?.let {
            it.lastEventSent = eventType
            it.refresh()
        }

        // Significant events trigger immediate flush
        val significant = setOf(EventTypes.PURCHASE, EventTypes.SUBSCRIBE)
        if (eventType in significant) {
            scope.launch { eventQueue?.flush() }
        }
    }

    /**
     * Show a floating debug overlay on the given activity with SDK status info.
     * Only works when logLevel is [LogLevel.DEBUG].
     */
    @JvmStatic
    fun showDebugOverlay(activity: Activity) {
        if (configuration?.logLevel != LogLevel.DEBUG) {
            Logger.warning("Debug overlay requires logLevel = DEBUG. Ignoring.")
            return
        }

        hideDebugOverlay()

        val overlay = DebugOverlay(activity)
        debugOverlay = overlay
        overlay.show()
        Logger.debug("Debug overlay shown.")
    }

    /**
     * Hide and remove the debug overlay.
     */
    @JvmStatic
    fun hideDebugOverlay() {
        debugOverlay?.hide()
        debugOverlay = null
    }

    /**
     * Internal check used by [DebugOverlay] to read configured state.
     */
    internal fun isConfiguredForDebug(): Boolean = isConfigured

    /**
     * Pass EchoStack attribution data to RevenueCat for campaign-based paywall targeting.
     * Call after both EchoStack and RevenueCat Purchases SDKs are configured.
     *
     * Sets subscriber attributes so RevenueCat can segment users by acquisition source:
     * - `$echoStackId` — EchoStack device ID
     * - `$mediaSource` — attributed ad network (e.g., "meta", "google")
     * - `$campaign` — campaign name
     * - `$adGroup` — ad set / ad group ID
     * - `$ad` — ad creative ID
     * - `$keyword` — search keyword (if applicable)
     *
     * TODO(partnership): When EchoStack becomes a recognized RevenueCat integration partner,
     * replace custom attributes with Purchases.sharedInstance.attribution.setEchoStackAttributionParams()
     */
    @JvmStatic
    fun syncWithRevenueCat() {
        if (!isConfigured || _isSdkDisabled) {
            Logger.warning("SDK not configured or disabled. Cannot sync with RevenueCat.")
            return
        }

        try {
            val purchasesClass = Class.forName("com.revenuecat.purchases.Purchases")
            val sharedInstance = purchasesClass.getMethod("getSharedInstance").invoke(null)

            val attributes = mutableMapOf<String, String>()

            getEchoStackId()?.let { attributes["\$echoStackId"] = it }

            getAttributionParams()?.let { attribution ->
                (attribution["network"] as? String)?.let { attributes["\$mediaSource"] = it }
                (attribution["campaign_name"] as? String)?.let { attributes["\$campaign"] = it }
                (attribution["adset_id"] as? String)?.let { attributes["\$adGroup"] = it }
                (attribution["ad_id"] as? String)?.let { attributes["\$ad"] = it }
                (attribution["keyword"] as? String)?.let { attributes["\$keyword"] = it }
            }

            if (attributes.isEmpty()) {
                Logger.debug("No attribution data to sync with RevenueCat.")
                return
            }

            // TODO(partnership): Replace with Purchases.sharedInstance.attribution.setEchoStackAttributionParams(attributes)
            val setAttributesMethod = sharedInstance.javaClass.getMethod(
                "setAttributes", Map::class.java
            )
            setAttributesMethod.invoke(sharedInstance, attributes)

            Logger.debug("Synced ${attributes.size} attributes with RevenueCat.")
        } catch (e: ClassNotFoundException) {
            Logger.warning("RevenueCat SDK not found. Add the RevenueCat dependency to use syncWithRevenueCat().")
        } catch (e: Exception) {
            Logger.error("Failed to sync with RevenueCat: ${e.message}")
        }
    }

    /**
     * Sync EchoStack attribution with Superwall for campaign-targeted paywalls.
     * Call after both SDKs are configured, before the first `Superwall.instance.register()` call.
     *
     * Sets the following Superwall user attributes:
     * - `echostack_id`: The unique device installation ID.
     * - Attribution parameters (network, campaign_id, campaign_name, etc.) when available.
     *
     * Requires the Superwall SDK to be linked in the host app. This is a no-op
     * if Superwall is not available at runtime.
     *
     * TODO(partnership): When EchoStack is a recognized Superwall partner, replace with
     * `Superwall.instance.setIntegrationAttribute(IntegrationAttribute.echoStackId, ...)`.
     */
    @JvmStatic
    fun syncWithSuperwall() {
        if (!isConfigured || _isSdkDisabled) {
            Logger.warning("SDK not configured or disabled. Cannot sync with Superwall.")
            return
        }

        val echoStackId = getEchoStackId()
        if (echoStackId == null) {
            Logger.warning("EchoStack ID not available. Cannot sync with Superwall.")
            return
        }

        try {
            val superwallClass = Class.forName("com.superwall.sdk.Superwall")
            val getInstance = superwallClass.getMethod("getInstance")
            val superwallInstance = getInstance.invoke(null)

            val setUserAttributes = superwallInstance.javaClass.getMethod(
                "setUserAttributes",
                Map::class.java
            )

            // TODO(partnership): Replace with Superwall.instance.setIntegrationAttribute(IntegrationAttribute.echoStackId, echoStackId)
            setUserAttributes.invoke(superwallInstance, mapOf("echostack_id" to echoStackId))
            Logger.debug("Superwall: set echostack_id")

            // Forward attribution parameters if available
            val attribution = getAttributionParams()
            if (attribution != null) {
                // TODO(partnership): Replace with Superwall.instance.setIntegrationAttribute() calls
                setUserAttributes.invoke(superwallInstance, attribution)
                Logger.debug("Superwall: set attribution params (${attribution.size} keys)")
            }
        } catch (e: ClassNotFoundException) {
            Logger.debug("Superwall SDK not available. Skipping Superwall sync.")
        } catch (e: Exception) {
            Logger.warning("Failed to sync with Superwall: ${e.message}")
        }
    }

    /**
     * Disable the SDK (called internally on 401 or fatal errors).
     */
    internal fun disable() {
        _isSdkDisabled = true
        Logger.error("EchoStack SDK disabled.")
    }

    private fun registerLifecycleCallbacks(context: Context) {
        val app = context as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                scope.launch { eventQueue?.flush() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

/** Predefined event type constants matching backend conventions. */
object EventTypes {
    const val INSTALL = "install"
    const val TRIAL_START = "trial_start"
    const val TRIAL_QUALIFIED = "trial_qualified"
    const val PURCHASE = "purchase"
    const val SUBSCRIBE = "subscribe"
    const val AD_IMPRESSION = "ad_impression"
    const val AD_CLICK = "ad_click"
    const val LOGIN = "login"
    const val SIGN_UP = "sign_up"
    const val REGISTER = "register"
    const val ADD_TO_CART = "add_to_cart"
    const val ADD_TO_WISHLIST = "add_to_wishlist"
    const val INITIATE_CHECKOUT = "initiate_checkout"
    const val LEVEL_START = "level_start"
    const val LEVEL_COMPLETE = "level_complete"
    const val TUTORIAL_COMPLETE = "tutorial_complete"
    const val SEARCH = "search"
    const val VIEW_ITEM = "view_item"
    const val VIEW_CONTENT = "view_content"
    const val SHARE = "share"
    const val CUSTOM = "custom"
}
