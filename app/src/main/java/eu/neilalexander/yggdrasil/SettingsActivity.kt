package eu.neilalexander.yggdrasil

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater

    private lateinit var deviceNameEntry: EditText
    private lateinit var exitModeSwitch: Switch
    private lateinit var exitRemoteAddrEntry: EditText
    private lateinit var exitRemotePortEntry: EditText
    private lateinit var exitLocalPortEntry: EditText
    private lateinit var exitDnsServersEntry: EditText
    private lateinit var exitExcludedAppsEntry: EditText
    private lateinit var publicKeyLabel: TextView
    private lateinit var resetConfigurationRow: LinearLayoutCompat
    private lateinit var appSettings: SharedPreferences
    private var publicKeyReset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = ConfigurationProxy(applicationContext)
        appSettings = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        inflater = LayoutInflater.from(this)

        deviceNameEntry = findViewById(R.id.deviceNameEntry)
        exitModeSwitch = findViewById(R.id.exitModeSwitch)
        exitRemoteAddrEntry = findViewById(R.id.exitRemoteAddrEntry)
        exitRemotePortEntry = findViewById(R.id.exitRemotePortEntry)
        exitLocalPortEntry = findViewById(R.id.exitLocalPortEntry)
        exitDnsServersEntry = findViewById(R.id.exitDnsServersEntry)
        exitExcludedAppsEntry = findViewById(R.id.exitExcludedAppsEntry)
        publicKeyLabel = findViewById(R.id.publicKeyLabel)
        resetConfigurationRow = findViewById(R.id.resetConfigurationRow)

        deviceNameEntry.doOnTextChanged { text, _, _, _ ->
            config.updateJSON { cfg ->
                val nodeInfo = cfg.optJSONObject("NodeInfo")
                if (nodeInfo == null) {
                    cfg.put("NodeInfo", JSONObject("{}"))
                }
                cfg.getJSONObject("NodeInfo").put("name", text)
            }
        }

        deviceNameEntry.setOnKeyListener { view, keyCode, event ->
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        }

        exitModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.edit().putBoolean(PREF_KEY_EXIT_MODE, isChecked).apply()
        }
        exitRemoteAddrEntry.doOnTextChanged { text, _, _, _ ->
            appSettings.edit().putString(PREF_KEY_EXIT_REMOTE_ADDR, text?.toString().orEmpty()).apply()
        }
        exitRemotePortEntry.doOnTextChanged { text, _, _, _ ->
            appSettings.edit().putString(PREF_KEY_EXIT_REMOTE_PORT, text?.toString().orEmpty()).apply()
        }
        exitLocalPortEntry.doOnTextChanged { text, _, _, _ ->
            appSettings.edit().putString(PREF_KEY_EXIT_LOCAL_PORT, text?.toString().orEmpty()).apply()
        }
        exitDnsServersEntry.doOnTextChanged { text, _, _, _ ->
            appSettings.edit().putString(PREF_KEY_EXIT_DNS_SERVERS, text?.toString().orEmpty()).apply()
        }
        exitExcludedAppsEntry.doOnTextChanged { text, _, _, _ ->
            appSettings.edit().putString(PREF_KEY_EXIT_EXCLUDED_APPS, text?.toString().orEmpty()).apply()
        }

        findViewById<View>(R.id.deviceNameTableRow).setOnKeyListener { view, keyCode, event ->
            Log.i("Key", keyCode.toString())
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    deviceNameEntry.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        resetConfigurationRow.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_resetconfig, null)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.settings_warning_title))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.settings_reset)) { dialog, _ ->
                config.resetJSON()
                updateView()
                dialog.dismiss()
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            builder.show()
        }

        findViewById<View>(R.id.resetKeysRow).setOnClickListener {
            config.resetKeys()
            publicKeyReset = true
            updateView()
        }

        findViewById<View>(R.id.setKeysRow).setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_set_keys, null)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            val privateKey = view.findViewById<EditText>(R.id.private_key)
            builder.setTitle(getString(R.string.set_keys))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.save)) { dialog, _ ->
                config.setKeys(privateKey.text.toString())
                updateView()
                dialog.dismiss()
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            builder.show()
        }

        publicKeyLabel.setOnLongClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", publicKeyLabel.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        updateView()
    }

    private fun updateView() {
        val json = config.getJSON()
        val nodeinfo = json.optJSONObject("NodeInfo")
        if (nodeinfo != null) {
            deviceNameEntry.setText(nodeinfo.getString("name"), TextView.BufferType.EDITABLE)
        } else {
            deviceNameEntry.setText("", TextView.BufferType.EDITABLE)
        }

        var key = json.optString("PrivateKey")
        if (key.isNotEmpty()) {
            key = key.substring(key.length / 2)
        }
        publicKeyLabel.text = key

        exitModeSwitch.isChecked = appSettings.getBoolean(PREF_KEY_EXIT_MODE, false)
        exitRemoteAddrEntry.setText(appSettings.getString(PREF_KEY_EXIT_REMOTE_ADDR, ""), TextView.BufferType.EDITABLE)
        exitRemotePortEntry.setText(appSettings.getString(PREF_KEY_EXIT_REMOTE_PORT, ""), TextView.BufferType.EDITABLE)
        exitLocalPortEntry.setText(appSettings.getString(PREF_KEY_EXIT_LOCAL_PORT, ""), TextView.BufferType.EDITABLE)
        exitDnsServersEntry.setText(appSettings.getString(PREF_KEY_EXIT_DNS_SERVERS, ""), TextView.BufferType.EDITABLE)
        exitExcludedAppsEntry.setText(appSettings.getString(PREF_KEY_EXIT_EXCLUDED_APPS, ""), TextView.BufferType.EDITABLE)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(PacketTunnelProvider.STATE_INTENT)
        )
        (application as GlobalApplication).subscribe()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    // To be able to get public key from running Yggdrasil we use this receiver, as we don't have this field in config
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.hasExtra("pubkey") && !publicKeyReset) {
                val tree = intent.getStringExtra("pubkey")
                if (tree != null && tree != "null") {
                    publicKeyLabel.text = intent.getStringExtra("pubkey")
                }
            }
        }
    }
}
