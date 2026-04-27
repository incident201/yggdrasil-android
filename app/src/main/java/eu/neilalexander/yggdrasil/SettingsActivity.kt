package io.yggdrasilvpn

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater

    private lateinit var deviceNameEntry: EditText
    private lateinit var exitModeSwitch: MaterialSwitch
    private lateinit var exitConfigSpinner: Spinner
    private lateinit var addExitConfigButton: View
    private lateinit var deleteExitConfigButton: View
    private lateinit var exitConfigNameEntry: EditText
    private lateinit var exitInnerIpEntry: EditText
    private lateinit var exitRemoteAddrEntry: EditText
    private lateinit var exitRemotePortEntry: EditText
    private lateinit var exitLocalPortEntry: EditText
    private lateinit var exitDnsServer1Entry: EditText
    private lateinit var exitDnsServer2Entry: EditText
    private lateinit var exitExcludedAppsButton: View
    private lateinit var exitExcludedAppsSummary: TextView
    private lateinit var publicKeyLabel: TextView
    private lateinit var resetConfigurationRow: View
    private lateinit var dnsValue: TextView
    private lateinit var appSettings: SharedPreferences
    private lateinit var excludedAppsRepository: ExcludedAppsRepository
    private var publicKeyReset = false
    private var launcherApps: List<ExcludedAppsRepository.LauncherApp> = emptyList()
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var areLauncherAppsLoaded = false
    private var isUpdatingConfigFields = false
    private var exitConfigs: MutableList<ExitVpnConfig> = mutableListOf()
    private var activeConfigId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = ConfigurationProxy(applicationContext)
        appSettings = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        excludedAppsRepository = ExcludedAppsRepository(packageManager)
        inflater = LayoutInflater.from(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        deviceNameEntry = findViewById(R.id.deviceNameEntry)
        exitModeSwitch = findViewById(R.id.exitModeSwitch)
        exitConfigSpinner = findViewById(R.id.exitConfigSpinner)
        addExitConfigButton = findViewById(R.id.addExitConfigButton)
        deleteExitConfigButton = findViewById(R.id.deleteExitConfigButton)
        exitConfigNameEntry = findViewById(R.id.exitConfigNameEntry)
        exitInnerIpEntry = findViewById(R.id.exitInnerIpEntry)
        exitRemoteAddrEntry = findViewById(R.id.exitRemoteAddrEntry)
        exitRemotePortEntry = findViewById(R.id.exitRemotePortEntry)
        exitLocalPortEntry = findViewById(R.id.exitLocalPortEntry)
        exitDnsServer1Entry = findViewById(R.id.exitDnsServer1Entry)
        exitDnsServer2Entry = findViewById(R.id.exitDnsServer2Entry)
        exitExcludedAppsButton = findViewById(R.id.exitExcludedAppsButton)
        exitExcludedAppsSummary = findViewById(R.id.exitExcludedAppsSummary)
        publicKeyLabel = findViewById(R.id.publicKeyLabel)
        resetConfigurationRow = findViewById(R.id.resetConfigurationRow)
        dnsValue = findViewById(R.id.dnsValue)

        deviceNameEntry.doOnTextChanged { text, _, _, _ ->
            config.updateJSON { cfg ->
                val nodeInfo = cfg.optJSONObject("NodeInfo")
                if (nodeInfo == null) {
                    cfg.put("NodeInfo", JSONObject("{}"))
                }
                cfg.getJSONObject("NodeInfo").put("name", text)
            }
        }

        deviceNameEntry.setOnKeyListener { _, keyCode, _ ->
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        }

        exitModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.edit().putBoolean(PREF_KEY_EXIT_MODE, isChecked).apply()
        }
        exitConfigSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = exitConfigs.getOrNull(position) ?: return
                if (selected.id != activeConfigId) {
                    activeConfigId = selected.id
                    ExitVpnConfigStore.persist(appSettings, exitConfigs, activeConfigId)
                    bindActiveConfigToFields()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        addExitConfigButton.setOnClickListener { addExitConfig() }
        deleteExitConfigButton.setOnClickListener { confirmDeleteActiveConfig() }

        exitInnerIpEntry.filters = arrayOf(InputFilter.LengthFilter(15))

        val saveActiveConfigFromFields = fun() {
            if (isUpdatingConfigFields) {
                return
            }
            val index = exitConfigs.indexOfFirst { it.id == activeConfigId }
            if (index < 0) {
                return
            }
            val innerIp = exitInnerIpEntry.text?.toString().orEmpty()
            val innerIpError = validateExitInnerIp(innerIp)
            exitInnerIpEntry.error = innerIpError
            val updated = exitConfigs[index].copy(
                displayName = exitConfigNameEntry.text?.toString().orEmpty(),
                innerIp = innerIp,
                remoteAddr = exitRemoteAddrEntry.text?.toString().orEmpty(),
                remotePort = exitRemotePortEntry.text?.toString().orEmpty(),
                localPort = exitLocalPortEntry.text?.toString().orEmpty(),
                dnsServer1 = exitDnsServer1Entry.text?.toString().orEmpty(),
                dnsServer2 = exitDnsServer2Entry.text?.toString().orEmpty()
            )
            val displayNameChanged = exitConfigs[index].displayName != updated.displayName
            exitConfigs[index] = updated
            ExitVpnConfigStore.persist(appSettings, exitConfigs, activeConfigId)
            if (displayNameChanged) {
                refreshExitConfigSpinner()
            }
        }

        exitConfigNameEntry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitInnerIpEntry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitRemoteAddrEntry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitRemotePortEntry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitLocalPortEntry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitDnsServer1Entry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }
        exitDnsServer2Entry.doOnTextChanged { _, _, _, _ -> saveActiveConfigFromFields() }

        exitExcludedAppsButton.setOnClickListener { showExcludedAppsDialog() }

        findViewById<View>(R.id.dnsRow).setOnClickListener {
            startActivity(Intent(this, DnsActivity::class.java))
        }

        resetConfigurationRow.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_resetconfig, null)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.settings_warning_title))
                .setView(view)
                .setPositiveButton(getString(R.string.settings_reset)) { dialog, _ ->
                    config.resetJSON()
                    updateView()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .show()
        }

        findViewById<View>(R.id.resetKeysRow).setOnClickListener {
            config.resetKeys()
            publicKeyReset = true
            updateView()
        }

        findViewById<View>(R.id.setKeysRow).setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_set_keys, null)
            val privateKey = view.findViewById<EditText>(R.id.private_key)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.set_keys))
                .setView(view)
                .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                    config.setKeys(privateKey.text.toString())
                    updateView()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .show()
        }

        publicKeyLabel.setOnLongClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", publicKeyLabel.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        val languageRadioGroup = findViewById<RadioGroup>(R.id.languageRadioGroup)
        val savedLang = appSettings.getString("app_language", "")
        when (savedLang) {
            "ru" -> languageRadioGroup.check(R.id.radioRussian)
            "en" -> languageRadioGroup.check(R.id.radioEnglish)
            else -> {
                val currentLang = resources.configuration.locales[0].language
                if (currentLang == "ru") languageRadioGroup.check(R.id.radioRussian)
                else languageRadioGroup.check(R.id.radioEnglish)
            }
        }
        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = when (checkedId) {
                R.id.radioRussian -> "ru"
                else -> "en"
            }
            appSettings.edit().putString("app_language", lang).apply()
            setAppLocale(lang)
            recreate()
        }

        updateView()
        loadLauncherApps(filterSystemApps = true)
    }

    private fun addExitConfig() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(getString(R.string.exit_vpn_new_server_default_name, exitConfigs.size + 1))
            setSelection(text?.length ?: 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.exit_vpn_config_name)
            addView(input)
        }
        val dialogContent = FrameLayout(this).apply {
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.dialog_content_padding_horizontal)
            val topPadding = resources.getDimensionPixelSize(R.dimen.dialog_content_padding_top)
            setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
            addView(
                inputLayout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_vpn_config_add)
            .setView(dialogContent)
            .setPositiveButton(R.string.add) { dialog, _ ->
                val created = ExitVpnConfigStore.defaultConfig(exitConfigs.size + 1).copy(
                    displayName = input.text?.toString()?.trim().orEmpty()
                )
                exitConfigs.add(created)
                activeConfigId = created.id
                ExitVpnConfigStore.persist(appSettings, exitConfigs, activeConfigId)
                refreshExitConfigSpinner()
                bindActiveConfigToFields()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun confirmDeleteActiveConfig() {
        if (exitConfigs.size <= 1) {
            Toast.makeText(this, R.string.exit_vpn_config_delete_last_error, Toast.LENGTH_SHORT).show()
            return
        }
        val activeConfig = exitConfigs.firstOrNull { it.id == activeConfigId } ?: return
        val title = getString(
            R.string.exit_vpn_config_delete_confirm_title,
            activeConfig.displayName.ifBlank { getString(R.string.exit_vpn_unnamed_config) }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(R.string.exit_vpn_config_delete_confirm_message)
            .setPositiveButton(R.string.remove) { dialog, _ ->
                deleteActiveConfig()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun deleteActiveConfig() {
        val index = exitConfigs.indexOfFirst { it.id == activeConfigId }
        if (index < 0) return
        exitConfigs.removeAt(index)
        activeConfigId = exitConfigs.getOrNull((index - 1).coerceAtLeast(0))?.id.orEmpty()
        ExitVpnConfigStore.persist(appSettings, exitConfigs, activeConfigId)
        refreshExitConfigSpinner()
        bindActiveConfigToFields()
    }

    private fun bindActiveConfigToFields() {
        val activeConfig = exitConfigs.firstOrNull { it.id == activeConfigId } ?: return
        isUpdatingConfigFields = true
        exitConfigNameEntry.setText(activeConfig.displayName, TextView.BufferType.EDITABLE)
        exitInnerIpEntry.setText(activeConfig.innerIp, TextView.BufferType.EDITABLE)
        exitInnerIpEntry.error = validateExitInnerIp(activeConfig.innerIp)
        exitRemoteAddrEntry.setText(activeConfig.remoteAddr, TextView.BufferType.EDITABLE)
        exitRemotePortEntry.setText(activeConfig.remotePort, TextView.BufferType.EDITABLE)
        exitLocalPortEntry.setText(activeConfig.localPort, TextView.BufferType.EDITABLE)
        exitDnsServer1Entry.setText(activeConfig.dnsServer1, TextView.BufferType.EDITABLE)
        exitDnsServer2Entry.setText(activeConfig.dnsServer2, TextView.BufferType.EDITABLE)
        isUpdatingConfigFields = false
    }

    private fun refreshExitConfigSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            exitConfigs.map { it.displayName.ifBlank { getString(R.string.exit_vpn_unnamed_config) } }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        exitConfigSpinner.adapter = adapter
        val activeIndex = exitConfigs.indexOfFirst { it.id == activeConfigId }.coerceAtLeast(0)
        if (exitConfigSpinner.selectedItemPosition != activeIndex) {
            exitConfigSpinner.setSelection(activeIndex)
        }
    }

    private fun validateExitInnerIp(innerIp: String): String? {
        val address = try {
            InetAddress.getByName(innerIp.trim())
        } catch (_: Exception) {
            null
        } as? Inet4Address ?: return getString(R.string.exit_vpn_inner_ip_error)
        val bytes = address.address
        val fourth = bytes[3].toInt() and 0xFF
        if (fourth == 0 || fourth == 1 || fourth == 255) {
            return getString(R.string.exit_vpn_inner_ip_error)
        }
        return null
    }

    private fun setAppLocale(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
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

        appSettings.edit().putBoolean(PREF_KEY_EXIT_MODE, true).apply()
        exitModeSwitch.isChecked = true

        val loaded = ExitVpnConfigStore.load(appSettings)
        exitConfigs = loaded.first.toMutableList()
        activeConfigId = loaded.second.orEmpty()
        refreshExitConfigSpinner()
        bindActiveConfigToFields()

        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            dnsValue.text = when (servers.size) {
                0 -> getString(R.string.dns_no_servers)
                1 -> getString(R.string.dns_one_server)
                else -> getString(R.string.dns_many_servers, servers.size)
            }
        } else {
            dnsValue.text = getString(R.string.dns_no_servers)
        }
    }

    private fun getGlobalExcludedPackagesFromSettings(): MutableSet<String> {
        return appSettings.getString(PREF_KEY_EXIT_EXCLUDED_APPS, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    private fun showExcludedAppsDialog() {
        if (!areLauncherAppsLoaded) {
            Toast.makeText(this, R.string.exit_vpn_excluded_apps_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPackages = getGlobalExcludedPackagesFromSettings()
        val listView = ListView(this)
        val adapter = ExcludedAppsAdapter(selectedPackages)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listView.setOnItemClickListener { _, _, position, _ ->
            val packageName = launcherApps[position].packageName
            if (selectedPackages.contains(packageName)) {
                selectedPackages.remove(packageName)
            } else {
                selectedPackages.add(packageName)
            }
            adapter.notifyDataSetChanged()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.exit_vpn_excluded_apps))
            .setView(listView)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val value = selectedPackages.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(",")
                appSettings.edit().putString(PREF_KEY_EXIT_EXCLUDED_APPS, value).apply()
                updateExcludedAppsSummary()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun updateExcludedAppsSummary() {
        val selectedPackages = getGlobalExcludedPackagesFromSettings()
        if (selectedPackages.isEmpty()) {
            exitExcludedAppsSummary.text = getString(R.string.exit_vpn_excluded_apps_none)
            return
        }

        val packageToLabel = launcherApps.associate { it.packageName to it.label }
        val selectedLabels = selectedPackages.map { packageToLabel[it] ?: it }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        exitExcludedAppsSummary.text = if (selectedLabels.isEmpty()) {
            getString(R.string.exit_vpn_excluded_apps_none)
        } else {
            selectedLabels.joinToString("\n")
        }
    }

    private fun loadLauncherApps(filterSystemApps: Boolean) {
        backgroundExecutor.execute {
            val loadedApps = excludedAppsRepository.loadLauncherApps(filterSystemApps)
            mainThreadHandler.post {
                launcherApps = loadedApps.filter { it.packageName != packageName }
                areLauncherAppsLoaded = true
                updateExcludedAppsSummary()
            }
        }
    }

    private inner class ExcludedAppsAdapter(
        private val selectedPackages: Set<String>
    ) : BaseAdapter() {
        override fun getCount(): Int = launcherApps.size

        override fun getItem(position: Int): ExcludedAppsRepository.LauncherApp = launcherApps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemView = convertView ?: inflater.inflate(R.layout.item_excluded_app, parent, false)
            val app = getItem(position)
            itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            itemView.findViewById<TextView>(R.id.appLabel).text = app.label
            itemView.findViewById<CheckBox>(R.id.appCheckBox).isChecked =
                selectedPackages.contains(app.packageName)
            return itemView
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
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
