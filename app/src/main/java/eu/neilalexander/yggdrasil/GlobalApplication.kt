package io.yggdrasilvpn

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val PREF_KEY_ENABLED = "enabled"
const val PREF_KEY_PEERS_NOTE = "peers_note"
const val APP_SETTINGS_NAME = "app_settings"
const val PREF_KEY_EXIT_MODE = "exit_mode"
const val PREF_KEY_EXIT_REMOTE_ADDR = "exit_remote_addr"
const val PREF_KEY_EXIT_REMOTE_PORT = "exit_remote_port"
const val PREF_KEY_EXIT_LOCAL_PORT = "exit_local_port"
const val PREF_KEY_EXIT_DNS_SERVERS = "exit_dns_servers"
const val PREF_KEY_EXIT_EXCLUDED_APPS = "exit_excluded_apps"
const val PREF_KEY_EXIT_CONFIGS = "exit_configs"
const val PREF_KEY_EXIT_ACTIVE_CONFIG_ID = "exit_active_config_id"
const val MAIN_CHANNEL_ID = "Yggdrasil Service"

data class ExitVpnConfig(
    val id: String,
    val displayName: String,
    val innerIp: String,
    val remoteAddr: String,
    val remotePort: String,
    val localPort: String,
    val dnsServer1: String,
    val dnsServer2: String,
)

object ExitVpnConfigStore {
    fun load(preferences: SharedPreferences): Pair<List<ExitVpnConfig>, String?> {
        val configsRaw = preferences.getString(PREF_KEY_EXIT_CONFIGS, "[]").orEmpty()
        val parsedConfigs = mutableListOf<ExitVpnConfig>()
        var migratedExcludedApps = ""
        try {
            val array = JSONArray(configsRaw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                if (migratedExcludedApps.isBlank()) {
                    migratedExcludedApps = item.optString("excludedApps", "")
                }
                parsedConfigs.add(
                    ExitVpnConfig(
                        id = id,
                        displayName = item.optString("name", ""),
                        innerIp = item.optString("innerIp", "10.66.0.2"),
                        remoteAddr = item.optString("remoteAddr", ""),
                        remotePort = item.optString("remotePort", ""),
                        localPort = item.optString("localPort", ""),
                        dnsServer1 = item.optString("dnsServer1", ""),
                        dnsServer2 = item.optString("dnsServer2", "")
                    )
                )
            }
        } catch (_: Exception) {
            // fallback to migration path below
        }

        val configs = if (parsedConfigs.isNotEmpty()) parsedConfigs else listOf(migrateLegacy(preferences))
        val activeIdPref = preferences.getString(PREF_KEY_EXIT_ACTIVE_CONFIG_ID, "")?.trim().orEmpty()
        val activeId = if (configs.any { it.id == activeIdPref }) activeIdPref else configs.first().id
        if (preferences.getString(PREF_KEY_EXIT_EXCLUDED_APPS, "").isNullOrBlank() && migratedExcludedApps.isNotBlank()) {
            preferences.edit().putString(PREF_KEY_EXIT_EXCLUDED_APPS, migratedExcludedApps).apply()
        }
        persist(preferences, configs, activeId)
        return configs to activeId
    }

    fun getActive(preferences: SharedPreferences): ExitVpnConfig {
        val (configs, activeId) = load(preferences)
        return configs.firstOrNull { it.id == activeId } ?: configs.first()
    }

    fun persist(preferences: SharedPreferences, configs: List<ExitVpnConfig>, activeId: String) {
        val normalized = if (configs.isEmpty()) listOf(defaultConfig(1)) else configs
        val safeActiveId = if (normalized.any { it.id == activeId }) activeId else normalized.first().id
        val array = JSONArray()
        normalized.forEach { config ->
            array.put(JSONObject().apply {
                put("id", config.id)
                put("name", config.displayName)
                put("innerIp", config.innerIp)
                put("remoteAddr", config.remoteAddr)
                put("remotePort", config.remotePort)
                put("localPort", config.localPort)
                put("dnsServer1", config.dnsServer1)
                put("dnsServer2", config.dnsServer2)
            })
        }
        preferences.edit()
            .putString(PREF_KEY_EXIT_CONFIGS, array.toString())
            .putString(PREF_KEY_EXIT_ACTIVE_CONFIG_ID, safeActiveId)
            .apply()
    }

    fun defaultConfig(number: Int): ExitVpnConfig {
        return ExitVpnConfig(
            id = UUID.randomUUID().toString(),
            displayName = "Server $number",
            innerIp = "10.66.0.2",
            remoteAddr = "",
            remotePort = "",
            localPort = "",
            dnsServer1 = "",
            dnsServer2 = ""
        )
    }

    private fun migrateLegacy(preferences: SharedPreferences): ExitVpnConfig {
        val dnsServers = preferences.getString(PREF_KEY_EXIT_DNS_SERVERS, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return ExitVpnConfig(
            id = UUID.randomUUID().toString(),
            displayName = "Server 1",
            innerIp = "10.66.0.2",
            remoteAddr = preferences.getString(PREF_KEY_EXIT_REMOTE_ADDR, "").orEmpty(),
            remotePort = preferences.getString(PREF_KEY_EXIT_REMOTE_PORT, "").orEmpty(),
            localPort = preferences.getString(PREF_KEY_EXIT_LOCAL_PORT, "").orEmpty(),
            dnsServer1 = dnsServers.getOrNull(0).orEmpty(),
            dnsServer2 = dnsServers.getOrNull(1).orEmpty()
        )
    }
}

class GlobalApplication: Application(), YggStateReceiver.StateReceiver {
    private lateinit var config: ConfigurationProxy
    private var currentState: State = State.Disabled
    private var updaterConnections: Int = 0

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
        val callback = NetworkStateCallback(this)
        callback.register()
        val receiver = YggStateReceiver(this)
        receiver.register(this)
        migrateDnsServers(this)
        ExitVpnConfigStore.load(getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE))
    }

    fun subscribe() {
        updaterConnections++
    }

    fun unsubscribe() {
        if (updaterConnections > 0) {
            updaterConnections--
        }
    }

    fun needUiUpdates(): Boolean {
        return updaterConnections > 0
    }

    fun getCurrentState(): State {
        return currentState
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStateChange(state: State) {
        if (state != currentState) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val componentName = ComponentName(this, YggTileService::class.java)
                TileService.requestListeningState(this, componentName)
            }

            if (state != State.Disabled) {
                val notification = createServiceNotification(this, state)
                val notificationManager: NotificationManager =
                    this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
            }

            currentState = state
        }
    }
}

fun migrateDnsServers(context: Context) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    if (preferences.getInt(KEY_DNS_VERSION, 0) >= 1) {
        return
    }
    val serverString = preferences.getString(KEY_DNS_SERVERS, "")
    if (serverString!!.isNotEmpty()) {
        val newServers = serverString
            .replace("300:6223::53", "308:25:40:bd::")
            .replace("302:7991::53", "308:62:45:62::")
            .replace("302:db60::53", "308:84:68:55::")
            .replace("301:1088::53", "308:c8:48:45::")
        val editor = preferences.edit()
        editor.putInt(KEY_DNS_VERSION, 1)
        if (newServers != serverString) {
            editor.putString(KEY_DNS_SERVERS, newServers)
        }
        editor.apply()
    }
}

fun createServiceNotification(context: Context, state: State): Notification {
    createNotificationChannels(context)

    // Intent to open the app
    val openIntent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, openIntent, flags)

    // Disconnect action intent
    val disconnectIntent = Intent(context, PacketTunnelProvider::class.java).apply {
        action = PacketTunnelProvider.ACTION_STOP
    }
    val disconnectFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    val disconnectPendingIntent: PendingIntent = PendingIntent.getService(
        context, 1, disconnectIntent, disconnectFlags
    )

    val title = when (state) {
        State.Disabled -> context.getText(R.string.tile_disabled)
        State.Enabled -> context.getText(R.string.notification_connecting)
        State.Connected -> context.getText(R.string.notification_connected)
        else -> context.getText(R.string.tile_disabled)
    }

    val builder = NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle(title)
        .setSmallIcon(R.drawable.ic_tile_icon)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    // Add disconnect action button when VPN is active
    if (state == State.Enabled || state == State.Connected) {
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_stop,
                context.getString(R.string.notification_disconnect),
                disconnectPendingIntent
            ).build()
        )
    }

    return builder.build()
}

fun createPermissionMissingNotification(context: Context): Notification {
    createNotificationChannels(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle(context.getText(R.string.app_name))
        .setContentText(context.getText(R.string.permission_notification_text))
        .setSmallIcon(R.drawable.ic_tile_icon)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
}

private fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = context.getString(R.string.channel_name)
        val descriptionText = context.getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
