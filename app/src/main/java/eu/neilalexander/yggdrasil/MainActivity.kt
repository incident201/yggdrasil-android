package eu.neilalexander.yggdrasil

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.neilalexander.yggdrasil.BuildConfig
import eu.neilalexander.yggdrasil.PacketTunnelProvider.Companion.STATE_INTENT
import mobile.Mobile
import org.json.JSONArray
import java.util.Locale

const val APP_WEB_URL = "https://github.com/yggdrasil-network/yggdrasil-android"
private const val PREF_NOTIFICATION_ASKED = "notification_permission_asked"

class MainActivity : AppCompatActivity() {
    private lateinit var vpnButton: ImageButton
    private lateinit var vpnButtonRing: View
    private lateinit var statusText: TextView
    private lateinit var connectionModeText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var subnetLabel: TextView
    private lateinit var subnetText: TextView
    private lateinit var copyIpButton: ImageButton
    private lateinit var peersCountText: TextView
    private lateinit var versionText: TextView

    private var isVpnEnabled = false
    private var isConnected = false

    private fun start() {
        val intent = Intent(this, PacketTunnelProvider::class.java)
        intent.action = PacketTunnelProvider.ACTION_START
        startService(intent)
    }

    private var startVpnActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            start()
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Permission granted or denied — we just proceed either way
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)

        vpnButton = findViewById(R.id.vpnButton)
        vpnButtonRing = findViewById(R.id.vpnButtonRing)
        statusText = findViewById(R.id.statusText)
        connectionModeText = findViewById(R.id.connectionModeText)
        ipAddressText = findViewById(R.id.ipAddressText)
        subnetLabel = findViewById(R.id.subnetLabel)
        subnetText = findViewById(R.id.subnetText)
        copyIpButton = findViewById(R.id.copyIpButton)
        peersCountText = findViewById(R.id.peersCountText)
        versionText = findViewById(R.id.versionText)

        // Show app version: use BuildConfig.VERSION_NAME + Go library version
        val appVersion = BuildConfig.VERSION_NAME
        val goVersion = try {
            val v = Mobile.getVersion()
            if (v.isNullOrEmpty() || v == "unknown") "" else v
        } catch (e: Exception) { "" }
        val displayVersion = if (goVersion.isNotEmpty()) {
            "$appVersion ($goVersion)"
        } else {
            appVersion
        }
        versionText.text = getString(R.string.version_label) + ": " + displayVersion

        // ExitVPN is always on — ensure the pref is set
        val appPreferences = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        appPreferences.edit().putBoolean(PREF_KEY_EXIT_MODE, true).apply()
        connectionModeText.text = getString(R.string.mode_exit_vpn)

        // VPN button click
        vpnButton.setOnClickListener {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
            val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
            if (!enabled) {
                // Start VPN
                preferences.edit(commit = true) { putBoolean(PREF_KEY_ENABLED, true) }
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    startVpnActivity.launch(vpnIntent)
                } else {
                    start()
                }
            } else {
                // Stop VPN
                preferences.edit(commit = true) { putBoolean(PREF_KEY_ENABLED, false) }
                val intent = Intent(this, PacketTunnelProvider::class.java)
                intent.action = PacketTunnelProvider.ACTION_STOP
                startService(intent)
            }
        }

        // Copy IP button
        copyIpButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ip", ipAddressText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Peers row
        findViewById<View>(R.id.peersRow).setOnClickListener {
            startActivity(Intent(this, PeersActivity::class.java))
        }

        // Settings row
        findViewById<View>(R.id.settingsRow).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Long press on IP to copy
        ipAddressText.setOnLongClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ip", ipAddressText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        updateButtonState(false, false)

        // Request notification permission on first launch (Android 13+)
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTIFICATION_ASKED, false)) return

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply()
            return
        }

        // Show rationale dialog first
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.notification_permission_title))
            .setMessage(getString(R.string.notification_permission_message))
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
                prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply()
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(STATE_INTENT)
        )
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        isVpnEnabled = preferences.getBoolean(PREF_KEY_ENABLED, false)

        // ExitVPN always on
        connectionModeText.text = getString(R.string.mode_exit_vpn)

        if (!isVpnEnabled) {
            updateButtonState(false, false)
            ipAddressText.text = getString(R.string.not_connected)
            copyIpButton.visibility = View.GONE
            subnetLabel.visibility = View.GONE
            subnetText.visibility = View.GONE
        }

        (application as GlobalApplication).subscribe()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    private fun updateButtonState(started: Boolean, connected: Boolean) {
        isConnected = connected
        if (started && connected) {
            statusText.text = getString(R.string.status_connected)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.vpn_connected))
            vpnButton.setBackgroundResource(R.drawable.vpn_button_background_connected)
            updateRingColor(R.color.vpn_button_connected)
        } else if (started) {
            statusText.text = getString(R.string.status_no_connectivity)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.vpn_connecting))
            vpnButton.setBackgroundResource(R.drawable.vpn_button_background_connected)
            updateRingColor(R.color.vpn_connecting)
        } else {
            statusText.text = getString(R.string.status_disconnected)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.vpn_disconnected))
            vpnButton.setBackgroundResource(R.drawable.vpn_button_background)
            updateRingColor(R.color.vpn_button_disconnected)
        }
    }

    private fun updateRingColor(colorRes: Int) {
        val drawable = vpnButtonRing.background
        if (drawable is GradientDrawable) {
            drawable.setStroke(
                (3 * resources.displayMetrics.density).toInt(),
                ContextCompat.getColor(this, colorRes)
            )
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.getStringExtra("type")) {
                "state" -> {
                    val started = intent.getBooleanExtra("started", false)
                    val peerState = JSONArray(intent.getStringExtra("peers") ?: "[]")
                    var count = 0
                    for (i in 0..<peerState.length()) {
                        val peer = peerState.getJSONObject(i)
                        if (peer.getString("IP").isNotEmpty()) {
                            count += 1
                        }
                    }

                    if (started) {
                        showPeersNoteIfNeeded(peerState.length())
                        updateButtonState(true, count > 0)
                        ipAddressText.text = intent.getStringExtra("ip") ?: "N/A"
                        copyIpButton.visibility = View.VISIBLE
                        val subnet = intent.getStringExtra("subnet") ?: ""
                        if (subnet.isNotEmpty() && subnet != "N/A") {
                            subnetLabel.visibility = View.VISIBLE
                            subnetText.visibility = View.VISIBLE
                            subnetText.text = subnet
                        }
                    } else {
                        updateButtonState(false, false)
                        ipAddressText.text = getString(R.string.not_connected)
                        copyIpButton.visibility = View.GONE
                        subnetLabel.visibility = View.GONE
                        subnetText.visibility = View.GONE
                    }

                    peersCountText.text = when (count) {
                        0 -> getString(R.string.main_no_peers)
                        1 -> getString(R.string.main_one_peer)
                        else -> getString(R.string.main_many_peers, count)
                    }
                }
            }
        }
    }

    private fun showPeersNoteIfNeeded(peerCount: Int) {
        if (peerCount > 0) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(this@MainActivity.baseContext)
        if (!preferences.getBoolean(PREF_KEY_PEERS_NOTE, false)) {
            this@MainActivity.runOnUiThread {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.main_add_some_peers_title))
                    .setMessage(getString(R.string.main_add_some_peers_message))
                    .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
            preferences.edit().apply {
                putBoolean(PREF_KEY_PEERS_NOTE, true)
                commit()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        val lang = prefs.getString("app_language", "")
        if (!lang.isNullOrEmpty()) {
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    fun openUrlInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getText(R.string.no_browser_found_toast), Toast.LENGTH_SHORT).show()
        }
    }
}
