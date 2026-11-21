package com.euwify.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.euwify.app.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.concurrent.Executors
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var api: LocalApi
    private lateinit var settings: Settings
    private lateinit var creds: CredentialStore

    private val background = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadExecutor() // status checks never wait behind slow calls
    @Volatile private var pollBusy = false
    private val main = Handler(Looper.getMainLooper())
    private var lastCaptchaId: String? = null
    private var eufyState = ""
    private var activityExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // phone's own colors on Android 12+
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep content clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        settings = Settings(this)
        creds = CredentialStore(this)
        api = NodeRunner.api(this)

        // The service starts Node and does all background work.
        GuardService.start(this)

        binding.setupButton.setOnClickListener { fixNextSetupItem() }

        binding.loginButton.setOnClickListener {
            val email = binding.email.text?.toString()?.trim().orEmpty()
            val password = binding.password.text?.toString().orEmpty()
            val country = binding.country.text?.toString()?.trim()?.uppercase().orEmpty()
            if (email.isEmpty() || password.isEmpty() || country.length != 2) {
                toast("Enter email, password and a 2-letter country code")
                return@setOnClickListener
            }
            creds.save(CredentialStore.Login(email, password, country))
            binding.password.text?.clear() // do not keep the password on screen
            GuardService.start(this, GuardService.ACTION_LOGIN_NOW)
            toast("Signing in…")
        }
        binding.tfaButton.setOnClickListener {
            send("/verify", JSONObject().put("code", binding.tfaCode.text?.toString()?.trim().orEmpty()))
            binding.tfaCode.text?.clear()
        }
        binding.captchaButton.setOnClickListener {
            send("/captcha", JSONObject().put("code", binding.captchaCode.text?.toString()?.trim().orEmpty()))
            binding.captchaCode.text?.clear()
        }

        binding.chooseCameraButton.setOnClickListener { loadCameras() }

        binding.addCurrentWifiButton.setOnClickListener {
            val names = GuardState.ssids.filter { it.isNotEmpty() }
            when {
                names.isNotEmpty() -> addHomeSsid(names.first())
                GuardState.ssids.isNotEmpty() ->
                    toast("Android hides the Wi-Fi name. Finish setup first (location).")
                else -> toast("You are not connected to any Wi-Fi")
            }
        }
        binding.ssidLayout.setEndIconOnClickListener { addTypedSsid() }
        binding.ssidInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { addTypedSsid(); true } else false
        }

        binding.autoSwitch.isChecked = settings.autoEnabled
        binding.autoSwitch.setOnCheckedChangeListener { _, on ->
            settings.autoEnabled = on
            GuardService.start(this, GuardService.ACTION_REFRESH)
        }

        binding.homeButton.setOnClickListener { setModeNow("home") }
        binding.awayButton.setOnClickListener { setModeNow("away") }
        binding.logoutButton.setOnClickListener { confirmLogout() }
        binding.revolutButton.setOnClickListener { openLink(REVOLUT_URL) }
        binding.paypalButton.setOnClickListener { openLink(PAYPAL_URL) }
        binding.clearActivityButton.setOnClickListener {
            background.execute { ActivityLog.clear(this) }
            binding.activityText.text = "Nothing yet."
        }
        binding.moreActivityButton.setOnClickListener {
            activityExpanded = !activityExpanded
            binding.activityText.maxLines = if (activityExpanded) Int.MAX_VALUE else 12
            binding.moreActivityButton.text = if (activityExpanded) "Show less" else "Show more"
        }

        renderCamera()
        renderHomeList()
    }

    override fun onResume() {
        super.onResume()
        renderChecklist()
        main.post(statusPoller)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(statusPoller)
    }

    // ---------------- status ----------------

    private val statusPoller = object : Runnable {
        override fun run() {
            if (!pollBusy) {
                pollBusy = true
                poller.execute {
                    val r = api.get("/status", timeoutMs = 5_000)
                    val activity = ActivityLog.read(this@MainActivity)
                    pollBusy = false
                    main.post {
                        showStatus(r)
                        // Don't replace the text while the user is selecting some of it.
                        if (!binding.activityText.hasSelection()) binding.activityText.text = activity
                    }
                }
            }
            main.postDelayed(this, 2_000)
        }
    }

    private fun color(attr: Int) = MaterialColors.getColor(binding.root, attr)

    private fun showStatus(r: LocalApi.Result) {
        val state = r.json.optString("state", "")
        eufyState = state
        val error = r.json.optString("error", "").takeIf { it.isNotBlank() && it != "null" }

        // --- hero card ---
        val mode = GuardState.confirmedMode
        val problem = state == "error" || state == "need_2fa" || state == "need_captcha" ||
            (!creds.hasLogin() && state.isNotEmpty())
        val (bg, fg, icon) = when {
            problem -> Triple(M.attr.colorErrorContainer, M.attr.colorOnErrorContainer, R.drawable.ic_warning)
            mode == "home" -> Triple(M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer, R.drawable.ic_home)
            mode == "away" -> Triple(M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer, R.drawable.ic_away)
            else -> Triple(M.attr.colorSurfaceContainerHighest, M.attr.colorOnSurface, R.drawable.ic_shield)
        }
        val fgColor = color(fg)
        binding.heroCard.setCardBackgroundColor(color(bg))
        binding.heroIcon.setImageResource(icon)
        binding.heroIcon.imageTintList = ColorStateList.valueOf(fgColor)
        binding.heroMode.text = when (mode) {
            "home" -> "Home"
            "away" -> "Away"
            else -> "—"
        }
        binding.heroCamera.text = settings.stationName ?: "No camera chosen"
        binding.summaryText.text = GuardState.summary
        listOf(binding.heroMode, binding.heroCamera, binding.summaryText, binding.lastEventText)
            .forEach { it.setTextColor(fgColor) }
        val last = GuardState.lastEvent
        binding.lastEventText.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
        binding.lastEventText.text = "Last: $last"

        val ssids = GuardState.ssids
        val names = ssids.filter { it.isNotEmpty() }
        binding.wifiChip.text = when {
            ssids.isEmpty() -> "Not on Wi-Fi"
            names.isEmpty() -> "Wi-Fi name hidden"
            names.any { it in settings.homeSsids } -> names.first() + " · home"
            else -> names.first()
        }
        binding.eufyChip.text = when (state) {
            "" -> "Starting…"
            "connected" -> "Eufy connected"
            "connecting" -> "Connecting…"
            "need_2fa", "need_captcha" -> "Code needed"
            "logged_out" -> "Not signed in"
            "error" -> "Sign-in problem"
            else -> state
        }
        binding.currentWifiText.text = when {
            ssids.isEmpty() -> "You are not on Wi-Fi right now."
            names.isEmpty() -> "You are on Wi-Fi, but Android hides its name. Finish setup."
            else -> "You are on: " + names.joinToString(", ")
        }

        // --- sign in / verification ---
        binding.loginCard.visibility =
            if (!creds.hasLogin() || state == "logged_out" || state == "error") View.VISIBLE else View.GONE
        binding.emailLayout.error = if (state == "error" && error != null) error else null

        val captcha = r.json.optJSONObject("captcha")
        val needCode = state == "need_2fa"
        val needCaptcha = state == "need_captcha" && captcha != null
        binding.verifyCard.visibility = if (needCode || needCaptcha) View.VISIBLE else View.GONE
        binding.tfaBox.visibility = if (needCode) View.VISIBLE else View.GONE
        binding.captchaBox.visibility = if (needCaptcha) View.VISIBLE else View.GONE
        if (needCaptcha) {
            val id = captcha!!.optString("id")
            if (id != lastCaptchaId) {
                lastCaptchaId = id
                val bytes = Base64.decode(captcha.optString("image").substringAfter(","), Base64.DEFAULT)
                binding.captchaImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        }

        // First time connected with no camera chosen: load the list by itself.
        if (state == "connected" && settings.stationSerial == null && binding.cameraGroup.childCount == 0) {
            loadCameras()
        }
    }

    // ---------------- setup checklist ----------------

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun needsNotifications() =
        Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun needsLocation() = !granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun needsBackgroundLocation() =
        Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun locationOff(): Boolean {
        val lm = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled
        else !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun batteryRestricted() =
        !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun renderChecklist() {
        val items = listOf(
            !needsNotifications() to "Notifications",
            !needsLocation() to "Location (to read the Wi-Fi name)",
            !needsBackgroundLocation() to "Location all the time (for the background)",
            !locationOff() to "Location turned on",
            !batteryRestricted() to "Battery not restricted",
        )
        binding.checklist.removeAllViews()
        for ((ok, text) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
                imageTintList = ColorStateList.valueOf(color(if (ok) androidx.appcompat.R.attr.colorPrimary else M.attr.colorOutline))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
            row.addView(TextView(this).apply {
                this.text = text
                setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                setPadding(dp(12), 0, 0, 0)
            })
            binding.checklist.addView(row)
        }
        val next = when {
            needsNotifications() -> "Allow notifications"
            needsLocation() -> "Allow location"
            needsBackgroundLocation() -> "Allow location all the time"
            locationOff() -> "Turn on location"
            batteryRestricted() -> "Don't restrict battery"
            else -> null
        }
        binding.setupCard.visibility = if (next == null) View.GONE else View.VISIBLE
        binding.setupButton.text = next ?: ""
    }

    private fun fixNextSetupItem() {
        when {
            needsNotifications() ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
            needsLocation() -> MaterialAlertDialogBuilder(this)
                .setTitle("Why location?")
                .setMessage(
                    "Android only tells apps the name of your Wi-Fi if they may use location. " +
                        "EuWiFy uses it only to see whether you are on your home Wi-Fi. " +
                        "It does not read GPS, and it does not save or share your location."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_PERMS,
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
            needsBackgroundLocation() -> MaterialAlertDialogBuilder(this)
                .setTitle("Location all the time")
                .setMessage(
                    "EuWiFy collects the name of the Wi-Fi network you are connected to, " +
                        "even when the app is closed or not in use, to switch your camera to " +
                        "Home when you arrive and to Away when you leave.\n\n" +
                        "On the next screen, choose \"Allow all the time\". " +
                        "The Wi-Fi name never leaves your phone."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_PERMS)
                }
                .setNegativeButton("Not now", null)
                .show()
            locationOff() -> startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
            batteryRestricted() -> requestBatteryExemption()
        }
    }

    @SuppressLint("BatteryLife") // this app must run all the time to do its job
    private fun requestBatteryExemption() {
        startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        renderChecklist()
        // Re-read the Wi-Fi now that we may see its name.
        GuardService.start(this, GuardService.ACTION_REFRESH)
    }

    // ---------------- camera ----------------

    private fun renderCamera() {
        binding.cameraText.text = settings.stationName ?: "No camera chosen yet"
        binding.chooseCameraButton.text = if (settings.stationName == null) "Choose" else "Change"
    }

    private fun loadCameras() {
        if (eufyState != "connected") {
            toast("Sign in first"); return
        }
        binding.cameraText.text = "Loading cameras…"
        background.execute {
            val r = api.get("/devices", timeoutMs = 30_000)
            main.post {
                renderCamera()
                if (r.code != 200) {
                    toast("Error: " + r.json.optString("error")); return@post
                }
                val stations = r.json.optJSONArray("stations") ?: return@post
                binding.cameraGroup.removeAllViews()
                for (i in 0 until stations.length()) {
                    val s = stations.getJSONObject(i)
                    val serial = s.optString("serial")
                    val name = s.optString("name")
                    val rb = RadioButton(this).apply {
                        id = View.generateViewId()
                        text = "$name  ·  ${s.optString("model")}  ·  now ${s.optString("guardMode")}"
                        isChecked = serial == settings.stationSerial
                        setOnClickListener { chooseCamera(serial, name) }
                    }
                    binding.cameraGroup.addView(rb)
                }
                if (stations.length() == 1 && settings.stationSerial == null) {
                    val s = stations.getJSONObject(0)
                    (binding.cameraGroup.getChildAt(0) as RadioButton).isChecked = true
                    chooseCamera(s.optString("serial"), s.optString("name"))
                }
                if (stations.length() == 0) toast("No cameras found on this account")
            }
        }
    }

    private fun chooseCamera(serial: String, name: String) {
        settings.stationSerial = serial
        settings.stationName = name
        renderCamera()
        GuardService.start(this, GuardService.ACTION_REFRESH)
    }

    // ---------------- home Wi-Fi ----------------

    private fun addTypedSsid() {
        val name = binding.ssidInput.text?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) addHomeSsid(name)
        binding.ssidInput.text?.clear()
    }

    private fun addHomeSsid(name: String) {
        settings.homeSsids = settings.homeSsids + name
        renderHomeList()
        GuardService.start(this, GuardService.ACTION_REFRESH)
    }

    private fun renderHomeList() {
        binding.homeChips.removeAllViews()
        val list = settings.homeSsids.sorted()
        binding.noHomeText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (name in list) {
            binding.homeChips.addView(Chip(this).apply {
                text = name
                chipIcon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_wifi)
                isCloseIconVisible = true
                closeIconContentDescription = "Remove $name"
                setOnCloseIconClickListener {
                    settings.homeSsids = settings.homeSsids - name
                    renderHomeList()
                    GuardService.start(this@MainActivity, GuardService.ACTION_REFRESH)
                }
            })
        }
    }

    // ---------------- manual switch ----------------

    private fun setModeNow(mode: String) {
        val serial = settings.stationSerial ?: run { toast("Choose a camera first"); return }
        binding.homeButton.isEnabled = false
        binding.awayButton.isEnabled = false
        binding.modeResult.visibility = View.VISIBLE
        binding.modeResult.text = "Switching to ${mode.replaceFirstChar { it.uppercase() }}… (can take up to a minute)"
        val started = System.currentTimeMillis()
        background.execute {
            val r = api.post(
                "/mode", JSONObject().put("station", serial).put("mode", mode),
                timeoutMs = 120_000,
            )
            val secs = (System.currentTimeMillis() - started) / 1000
            val j = r.json
            val ok = r.code == 200 && j.optBoolean("confirmed")
            val text = when {
                r.code != 200 -> "Error: " + j.optString("error")
                ok -> "${mode.replaceFirstChar { it.uppercase() }} ✓ confirmed by ${j.optString("via")} in $secs s"
                else -> "Not confirmed after ${j.optInt("attempts")} tries. Camera reports: ${j.optString("mode")}"
            }
            main.post {
                if (ok) GuardService.start(this, GuardService.ACTION_MANUAL, mode)
                binding.modeResult.text = text
                binding.homeButton.isEnabled = true
                binding.awayButton.isEnabled = true
            }
        }
    }

    // ---------------- 2FA / captcha / logout ----------------

    private fun send(path: String, body: JSONObject) {
        background.execute {
            val r = api.post(path, body)
            main.post { if (r.code != 200) toast(r.json.optString("error", "failed")) }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Sign out?")
            .setMessage("This deletes your Eufy login, the chosen camera and the Eufy session from this phone. Your home Wi-Fi list is kept.")
            .setPositiveButton("Sign out") { _, _ ->
                creds.clear()
                settings.clearCamera()
                binding.cameraGroup.removeAllViews()
                renderCamera()
                background.execute {
                    api.post("/logout")
                    main.post { toast("Signed out") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Opens a web link in the phone's browser (never inside the app). */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: android.content.ActivityNotFoundException) {
            toast("No browser found")
        }
    }

    private companion object {
        const val REQ_PERMS = 1
        // Optional tips. Must never unlock anything in the app (Google Play rule).
        const val REVOLUT_URL = "https://revolut.me/nobran"
        const val PAYPAL_URL = "https://www.paypal.com/paypalme/tayfunorge"
    }
}
