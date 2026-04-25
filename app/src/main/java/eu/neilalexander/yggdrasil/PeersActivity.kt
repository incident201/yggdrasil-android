package eu.neilalexander.yggdrasil

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

class PeersActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater
    private lateinit var peers: Array<JSONObject>

    private lateinit var connectedTableLayout: TableLayout
    private lateinit var connectedTableLabel: TextView
    private lateinit var configuredTableLayout: TableLayout
    private lateinit var configuredTableLabel: TextView
    private lateinit var multicastListenSwitch: MaterialSwitch
    private lateinit var multicastBeaconSwitch: MaterialSwitch
    private lateinit var passwordEdit: EditText
    private lateinit var addPeerButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peers)

        config = ConfigurationProxy(applicationContext)
        inflater = LayoutInflater.from(this)
        peers = emptyArray()

        // Toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.peersToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        connectedTableLayout = findViewById(R.id.connectedPeersTableLayout)
        connectedTableLabel = findViewById(R.id.connectedPeersLabel)

        configuredTableLayout = findViewById(R.id.configuredPeersTableLayout)
        configuredTableLabel = findViewById(R.id.configuredPeersLabel)

        val discoveryLink = findViewById<TextView>(R.id.peers_discovery_link)
        discoveryLink.movementMethod = LinkMovementMethod.getInstance()

        multicastListenSwitch = findViewById(R.id.enableMulticastListen)
        multicastListenSwitch.setOnCheckedChangeListener { button, _ ->
            config.multicastListen = button.isChecked
        }
        multicastBeaconSwitch = findViewById(R.id.enableMulticastBeacon)
        multicastBeaconSwitch.setOnCheckedChangeListener { button, _ ->
            config.multicastBeacon = button.isChecked
        }
        multicastListenSwitch.isChecked = config.multicastListen
        multicastBeaconSwitch.isChecked = config.multicastBeacon

        val multicastBeaconPanel = findViewById<TableRow>(R.id.enableMulticastBeaconPanel)
        multicastBeaconPanel.setOnClickListener {
            multicastBeaconSwitch.toggle()
        }
        val multicastListenPanel = findViewById<TableRow>(R.id.enableMulticastListenPanel)
        multicastListenPanel.setOnClickListener {
            multicastListenSwitch.toggle()
        }
        passwordEdit = findViewById(R.id.passwordEdit)
        passwordEdit.setText(config.multicastPassword)

        passwordEdit.doOnTextChanged { text, _, _, _ ->
            config.multicastPassword = text.toString()
        }

        passwordEdit.setOnKeyListener { _, keyCode, _ ->
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        }

        findViewById<View>(R.id.passwordTableRow).setOnKeyListener { _, keyCode, event ->
            Log.i("Key", keyCode.toString())
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    passwordEdit.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        addPeerButton = findViewById(R.id.addPeerButton)
        addPeerButton.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_addpeer, null)
            val input = view.findViewById<TextInputEditText>(R.id.addPeerInput)
            val inputLayout = view.findViewById<TextInputLayout>(R.id.addPeerInputLayout)
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.peers_add_peer))
                .setView(view)
                .setPositiveButton(getString(R.string.peers_add)) { dialog, _ ->
                    config.updateJSON { json ->
                        json.getJSONArray("Peers").put(input.text.toString().trim())
                    }
                    dialog.dismiss()
                    updateConfiguredPeers()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .create()
            dialog.show()
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false
            input.doOnTextChanged { text, _, _, _ ->
                val error = validatePeerUri(text.toString().trim())
                inputLayout?.error = error
                addButton.isEnabled = error == null && !text.isNullOrBlank()
            }
        }

        // Browse public peers button
        findViewById<View>(R.id.browsePublicPeersButton).setOnClickListener {
            startActivity(Intent(this, PublicPeersActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(PacketTunnelProvider.STATE_INTENT)
        )
        (application as GlobalApplication).subscribe()

        updateConfiguredPeers()
        updateConnectedPeers()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    private fun updateConfiguredPeers() {
        val peers = config.getJSON().getJSONArray("Peers")

        when (peers.length()) {
            0 -> {
                configuredTableLayout.visibility = View.GONE
                configuredTableLabel.text = getString(R.string.peers_no_configured_title)
            }
            else -> {
                configuredTableLayout.visibility = View.VISIBLE
                configuredTableLabel.text = getString(R.string.peers_configured_title)

                configuredTableLayout.removeAllViewsInLayout()
                for (i in 0 until peers.length()) {
                    val peer = peers[i].toString()
                    val view = inflater.inflate(R.layout.peers_configured, null)
                    view.findViewById<TextView>(R.id.addressValue).text = peer
                    view.findViewById<ImageButton>(R.id.deletePeerButton).tag = i

                    view.findViewById<ImageButton>(R.id.deletePeerButton).setOnClickListener { button ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.peers_remove_title, peer))
                            .setPositiveButton(getString(R.string.peers_remove)) { dialog, _ ->
                                config.updateJSON { json ->
                                    json.getJSONArray("Peers").remove(button.tag as Int)
                                }
                                dialog.dismiss()
                                updateConfiguredPeers()
                            }
                            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                                dialog.cancel()
                            }
                            .show()
                    }
                    configuredTableLayout.addView(view)
                }
            }
        }
    }

    private fun updateConnectedPeers() {
        when (peers.size) {
            0 -> {
                connectedTableLayout.visibility = View.GONE
                connectedTableLabel.text = getString(R.string.peers_no_connected_title)
            }
            else -> {
                var connected = false
                connectedTableLayout.removeAllViewsInLayout()
                for (peer in peers) {
                    val view = inflater.inflate(R.layout.peers_connected, null)
                    val ip = peer.getString("IP")
                    if (ip.isNotEmpty()) {
                        view.findViewById<TextView>(R.id.addressLabel).text = ip
                        view.findViewById<TextView>(R.id.detailsLabel).text = peer.getString("URI")
                        connectedTableLayout.addView(view)
                        connected = true
                    }
                }
                if (connected) {
                    connectedTableLayout.visibility = View.VISIBLE
                    connectedTableLabel.text = getString(R.string.peers_connected_title)
                } else {
                    connectedTableLayout.visibility = View.GONE
                    connectedTableLabel.text = getString(R.string.peers_no_connected_title)
                }
            }
        }
    }

    private val validSchemes = setOf("tcp", "tls", "quic", "ws", "wss", "socks")

    private fun validatePeerUri(input: String): String? {
        if (input.isEmpty()) return null
        val uri = try {
            URI(input)
        } catch (e: Exception) {
            return getString(R.string.peer_invalid_uri)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in validSchemes) {
            return getString(R.string.peer_invalid_scheme, validSchemes.joinToString(", "))
        }
        if (scheme != "socks") {
            if (uri.host.isNullOrEmpty()) {
                return getString(R.string.peer_missing_host)
            }
            if (uri.port == -1) {
                return getString(R.string.peer_missing_port)
            }
        }
        return null
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.getStringExtra("type")) {
                "state" -> {
                    if (intent.hasExtra("peers")) {
                        val peers1 = intent.getStringExtra("peers")
                        val peersArray = JSONArray(peers1 ?: "[]")
                        val array = Array(peersArray.length()) { i ->
                            peersArray.getJSONObject(i)
                        }
                        array.sortWith(compareBy { it.getString("IP") })
                        peers = array

                        updateConnectedPeers()
                    }
                }
            }
        }
    }
}
