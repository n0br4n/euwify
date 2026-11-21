package com.euwify.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the service is doing right now, for the screen to show. */
object GuardState {
    @Volatile var summary: String = "Starting…"
    @Volatile var ssids: List<String> = emptyList()  // "" = connected but name unknown
    @Volatile var confirmedMode: String? = null
    @Volatile var lastEvent: String = ""
}

/**
 * Foreground service: keeps Node running, logs in by itself with the saved
 * login, watches the Wi-Fi and switches the camera between Home and Away.
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "EuWiFy"
        const val ACTION_LOGIN_NOW = "com.euwify.app.LOGIN_NOW"
        const val ACTION_REFRESH = "com.euwify.app.REFRESH"
        /** The user switched by hand (extra "mode"); remember it as done. */
        const val ACTION_MANUAL = "com.euwify.app.MANUAL"
        private const val CH_STATUS = "status"
        private const val CH_EVENTS = "events"
        private const val ID_ONGOING = 1
        private const val ID_EVENT = 2
        private const val TICK_MS = 5_000L
        private const val RETRY_MODE_MS = 60_000L
        /** Wait this long after a Wi-Fi change before sending a command. */
        private const val SETTLE_MS = 8_000L

        fun start(context: Context, action: String? = null, mode: String? = null) {
            val i = Intent(context, GuardService::class.java).setAction(action)
            if (mode != null) i.putExtra("mode", mode)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var api: LocalApi
    private lateinit var settings: Settings
    private lateinit var creds: CredentialStore
    private lateinit var cm: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var nm: NotificationManager

    // Wi-Fi networks we are connected to -> name ("" if Android hides it)
    private val wifi = ConcurrentHashMap<Network, String>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    // Eufy login
    private var eufyState = ""
    private var stateSince = 0L
    private var loginInFlight = false
    private var nextLoginAt = 0L
    private var loginBackoffMin = 1L
    private var askedForCode = false

    // Guard mode
    private var awaySince: Long? = null
    private var desired: String? = null
    private var lastWant: String? = null
    private var modeInFlight = false
    private var nextModeAt = 0L
    private var failNotified = false
    @Volatile private var lastWifiChange = 0L   // when we joined/left a Wi-Fi
    private var lastModeSentAt = 0L

    private var lastOngoingText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        creds = CredentialStore(this)
        api = NodeRunner.api(this)
        cm = getSystemService(ConnectivityManager::class.java)
        wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        nm = getSystemService(NotificationManager::class.java)
        createChannels()

        try {
            ServiceCompat.startForeground(
                this, ID_ONGOING, ongoing("Starting…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not start in foreground", e)
            stopSelf()
            return
        }

        note("Service started")
        NodeRunner.start(this)
        thread = HandlerThread("guard").apply { start() }
        handler = Handler(thread.looper)
        registerWifi()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::handler.isInitialized) {
            when (intent?.action) {
                ACTION_LOGIN_NOW -> handler.post {
                    nextLoginAt = 0; loginBackoffMin = 1; askedForCode = false
                    login(force = true)
                }
                ACTION_REFRESH -> handler.post {
                    note("Settings or permissions changed, checking again")
                    registerWifi()
                    // re-check the mode with the new settings
                    desired = null; lastWant = null; nextModeAt = 0
                    GuardState.confirmedMode = null
                }
                ACTION_MANUAL -> {
                    val mode = intent.getStringExtra("mode")
                    handler.post {
                        note("Switched by hand to ${mode?.uppercase()}")
                        desired = mode; GuardState.confirmedMode = mode; nextModeAt = 0
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        if (::thread.isInitialized) thread.quitSafely()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------- main loop ----------------

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
                Log.e(TAG, "tick failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()

        // 1. Eufy login state
        val st = api.get("/status", timeoutMs = 5_000)
        val state = st.json.optString("state", "")
        if (state != eufyState) {
            eufyState = state; stateSince = now
            Log.i(TAG, "eufy state: $state")
            if (state.isNotEmpty()) note("Eufy: $state")
        }
        when (state) {
            "connected" -> { loginBackoffMin = 1; askedForCode = false }
            "logged_out", "error" -> login(force = false)
            "connecting" -> if (now - stateSince > 3 * 60_000) login(force = false)
            "need_2fa", "need_captcha" -> if (!askedForCode) {
                askedForCode = true
                event("Eufy needs a verification code. Open EuWiFy to enter it.")
            }
        }

        // 2. Where are we?
        val ssids = wifi.values.toList()
        GuardState.ssids = ssids
        val homes = settings.homeSsids
        val place = when {
            ssids.any { it.isNotEmpty() && it in homes } -> "home"
            ssids.any { it.isEmpty() } -> "unknown"
            else -> "away" // no Wi-Fi, or a Wi-Fi that is not home
        }
        val delayMs = settings.awayDelaySeconds * 1000L
        var want: String? = null
        when (place) {
            "home" -> { awaySince = null; want = "home" }
            "unknown" -> awaySince = null
            "away" -> {
                val since = awaySince ?: now.also { awaySince = it }
                if (now - since >= delayMs) want = "away"
            }
        }

        // 3. Switch if needed
        val serial = settings.stationSerial
        val ready = settings.autoEnabled && serial != null && homes.isNotEmpty() && state == "connected"
        if (ready) {
            // Act only when the Wi-Fi situation changes, so a manual
            // "Home now"/"Away now" is not undone right away.
            if (want != null && want != lastWant) {
                lastWant = want; desired = want; nextModeAt = 0; failNotified = false
                note(
                    if (want == "home") "On home Wi-Fi → should be HOME"
                    else "No home Wi-Fi for ${settings.awayDelaySeconds} s → should be AWAY"
                )
            }
            val d = desired
            val settled = now - lastWifiChange >= SETTLE_MS
            if (d != null && d != GuardState.confirmedMode && !modeInFlight && now >= nextModeAt && settled) {
                sendMode(serial!!, d)
            }
        }

        // 4. Ongoing notification
        val cam = settings.stationName ?: "Camera"
        val mode = GuardState.confirmedMode?.uppercase() ?: "?"
        val text = when {
            !creds.hasLogin() -> "Not logged in. Open EuWiFy."
            state == "need_2fa" || state == "need_captcha" -> "Eufy needs a code. Open EuWiFy."
            state == "" -> "Starting…"
            state != "connected" -> "Connecting to Eufy…"
            serial == null -> "Choose a camera in EuWiFy."
            homes.isEmpty() -> "Add your home Wi-Fi in EuWiFy."
            !settings.autoEnabled -> "Automatic switching is off."
            else -> "$cam: $mode · " + when (place) {
                "home" -> "on home Wi-Fi"
                "unknown" -> "Wi-Fi name hidden (Location off?)"
                else -> {
                    val left = (delayMs - (now - (awaySince ?: now))) / 1000
                    if (left > 0) "away from home Wi-Fi, Away in ${left}s" else "away from home"
                }
            }
        }
        GuardState.summary = text
        if (text != lastOngoingText) {
            lastOngoingText = text
            nm.notify(ID_ONGOING, ongoing(text))
        }
    }

    private fun login(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (loginInFlight || (!force && now < nextLoginAt)) return
        val login = creds.load() ?: return
        loginInFlight = true
        note(if (force) "Logging in to Eufy" else "Logging in to Eufy again (next try in ${loginBackoffMin} min if it fails)")
        // Wait longer after each failed try, so a wrong password is not hammered.
        nextLoginAt = now + loginBackoffMin * 60_000
        loginBackoffMin = (loginBackoffMin * 3).coerceAtMost(60)
        io.execute {
            val r = api.post(
                "/login", JSONObject()
                    .put("email", login.email)
                    .put("password", login.password)
                    .put("country", login.country),
                timeoutMs = 60_000,
            )
            Log.i(TAG, "login request: ${r.code}")
            handler.post { loginInFlight = false }
        }
    }

    private fun sendMode(serial: String, mode: String) {
        modeInFlight = true
        val cam = settings.stationName ?: "Camera"
        // A fresh camera connection if the network changed since the last command.
        val reconnect = lastWifiChange > lastModeSentAt
        lastModeSentAt = SystemClock.elapsedRealtime()
        note("Switching to ${mode.uppercase()}" + if (reconnect) " (fresh camera connection)" else "")
        io.execute {
            val r = api.post(
                "/mode",
                JSONObject().put("station", serial).put("mode", mode).put("reconnect", reconnect),
                timeoutMs = 120_000,
            )
            handler.post {
                modeInFlight = false
                val secs = (SystemClock.elapsedRealtime() - lastModeSentAt) / 1000
                if (r.code == 200 && r.json.optBoolean("confirmed")) {
                    note(
                        "${mode.uppercase()} confirmed by ${r.json.optString("via")}, " +
                            r.json.optInt("attempts").let { "$it ${if (it == 1) "try" else "tries"}" } +
                            ", $secs s" +
                            if (r.json.optBoolean("changed")) "" else " (was already ${mode.uppercase()})"
                    )
                    GuardState.confirmedMode = mode
                    // Also report it if we said "could not switch" before,
                    // so that message never stays wrong.
                    if (r.json.optBoolean("changed") || failNotified) {
                        failNotified = false
                        event("$cam → ${mode.uppercase()} ✓")
                    } else {
                        Log.i(TAG, "mode $mode was already set")
                    }
                } else {
                    note(
                        "${mode.uppercase()} NOT confirmed after $secs s: " +
                            (if (r.code == 200) "camera reports ${r.json.optString("mode")}"
                            else "error ${r.code} ${r.json.optString("error")}") +
                            ". Retry in 60 s"
                    )
                    nextModeAt = SystemClock.elapsedRealtime() + RETRY_MODE_MS
                    if (!failNotified) {
                        failNotified = true
                        event("$cam: could not switch to ${mode.uppercase()}, retrying every minute")
                    }
                }
            }
        }
    }

    // ---------------- Wi-Fi ----------------

    private fun registerWifi() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifi.clear()
        val onCaps = { network: Network, caps: NetworkCapabilities ->
            val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo
            else @Suppress("DEPRECATION") wifiManager.connectionInfo
            val ssid = cleanSsid(info?.ssid)
            // Capabilities also change for small things like signal strength;
            // only a new network or a new name counts as a change.
            if (wifi.put(network, ssid) != ssid) {
                lastWifiChange = SystemClock.elapsedRealtime()
                note(if (ssid.isEmpty()) "Joined a Wi-Fi (name hidden by Android)" else "Joined Wi-Fi \"$ssid\"")
            }
        }
        val onLost = { network: Network ->
            val old = wifi.remove(network)
            if (old != null) {
                lastWifiChange = SystemClock.elapsedRealtime()
                note(if (old.isEmpty()) "Left Wi-Fi" else "Left Wi-Fi \"$old\"")
            }
        }
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onCaps(n, c)
                override fun onLost(n: Network) = onLost(n)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onCaps(n, c)
                override fun onLost(n: Network) = onLost(n)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, cb, handler)
        callback = cb
    }

    private fun cleanSsid(raw: String?): String {
        if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>") return ""
        return raw.removeSurrounding("\"")
    }

    // ---------------- notifications ----------------

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Status (always shown)", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Mode changes and alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("EuWiFy")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    /** A line for "Recent activity" (and Logcat). */
    private fun note(text: String) {
        Log.i(TAG, text)
        ActivityLog.add(this, text)
    }

    private fun event(text: String) {
        note("Notification: $text")
        GuardState.lastEvent = text
        nm.notify(
            ID_EVENT,
            NotificationCompat.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle("EuWiFy")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }
}
