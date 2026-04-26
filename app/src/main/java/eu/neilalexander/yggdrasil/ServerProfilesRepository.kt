package eu.neilalexander.yggdrasil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String,
    val name: String,
    val ipv6Address: String,
    val remotePort: String,
    val localPort: String,
    val dnsServers: String
)

class ServerProfilesRepository(context: Context) {
    private val prefs = context.getSharedPreferences(APP_SETTINGS_NAME, Context.MODE_PRIVATE)

    fun getProfiles(): MutableList<ServerProfile> {
        ensureInitialized()
        return readProfilesRaw()
    }

    private fun readProfilesRaw(): MutableList<ServerProfile> {
        val raw = prefs.getString(PREF_KEY_SERVER_PROFILES, "[]").orEmpty()
        val json = JSONArray(raw)
        return MutableList(json.length()) { index ->
            val item = json.getJSONObject(index)
            ServerProfile(
                id = item.optString("id"),
                name = item.optString("name"),
                ipv6Address = item.optString("ipv6Address"),
                remotePort = item.optString("remotePort"),
                localPort = item.optString("localPort"),
                dnsServers = item.optString("dnsServers")
            )
        }
    }

    fun saveProfiles(profiles: List<ServerProfile>) {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("ipv6Address", profile.ipv6Address)
                    put("remotePort", profile.remotePort)
                    put("localPort", profile.localPort)
                    put("dnsServers", profile.dnsServers)
                }
            )
        }
        prefs.edit().putString(PREF_KEY_SERVER_PROFILES, json.toString()).apply()
        ensureActiveProfileExists()
    }

    fun getActiveProfileId(): String {
        ensureInitialized()
        return prefs.getString(PREF_KEY_ACTIVE_SERVER_ID, "").orEmpty()
    }

    fun setActiveProfile(profileId: String) {
        prefs.edit().putString(PREF_KEY_ACTIVE_SERVER_ID, profileId).apply()
    }

    fun getActiveProfile(): ServerProfile? {
        val activeId = getActiveProfileId()
        return getProfiles().firstOrNull { it.id == activeId }
    }

    private fun ensureInitialized() {
        val hasProfiles = prefs.contains(PREF_KEY_SERVER_PROFILES)
        if (hasProfiles) {
            ensureActiveProfileExists()
            return
        }
        val migrated = migrateLegacyProfile()
        saveProfiles(mutableListOf(migrated))
        setActiveProfile(migrated.id)
    }

    private fun ensureActiveProfileExists() {
        val profiles = readProfilesRaw()
        if (profiles.isEmpty()) {
            val fallback = createDefaultProfile()
            saveProfiles(listOf(fallback))
            setActiveProfile(fallback.id)
            return
        }
        val activeId = prefs.getString(PREF_KEY_ACTIVE_SERVER_ID, "").orEmpty()
        if (profiles.none { it.id == activeId }) {
            setActiveProfile(profiles.first().id)
        }
    }

    private fun migrateLegacyProfile(): ServerProfile {
        val legacyName = prefs.getString(PREF_KEY_ACTIVE_SERVER_NAME, "").orEmpty().ifBlank {
            "Default server"
        }
        val legacyAddr = prefs.getString(PREF_KEY_EXIT_REMOTE_ADDR, "").orEmpty()
        val legacyRemotePort = prefs.getString(PREF_KEY_EXIT_REMOTE_PORT, "").orEmpty()
        val legacyLocalPort = prefs.getString(PREF_KEY_EXIT_LOCAL_PORT, "").orEmpty()
        val legacyDns = prefs.getString(PREF_KEY_EXIT_DNS_SERVERS, "").orEmpty()
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = legacyName,
            ipv6Address = legacyAddr,
            remotePort = legacyRemotePort,
            localPort = legacyLocalPort,
            dnsServers = legacyDns
        )
    }

    private fun createDefaultProfile(): ServerProfile {
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = "Default server",
            ipv6Address = "",
            remotePort = "",
            localPort = "",
            dnsServers = ""
        )
    }
}
