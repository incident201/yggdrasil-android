package eu.neilalexander.yggdrasil

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class ServersActivity : AppCompatActivity() {
    private lateinit var repository: ServerProfilesRepository
    private lateinit var listView: ListView
    private lateinit var adapter: ServersAdapter
    private var profiles: MutableList<ServerProfile> = mutableListOf()
    private var activeProfileId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servers)

        repository = ServerProfilesRepository(this)
        listView = findViewById(R.id.serversList)

        val toolbar = findViewById<MaterialToolbar>(R.id.serversToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.addServerButton).setOnClickListener {
            showAddOrEditDialog()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val profile = profiles[position]
            repository.setActiveProfile(profile.id)
            activeProfileId = profile.id
            adapter.notifyDataSetChanged()
        }

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        profiles = repository.getProfiles()
        activeProfileId = repository.getActiveProfileId()
        adapter = ServersAdapter()
        listView.adapter = adapter
    }

    private fun showAddOrEditDialog(profile: ServerProfile? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server_profile, null)
        val nameEntry = view.findViewById<EditText>(R.id.serverNameEntry)
        val ipv6Entry = view.findViewById<EditText>(R.id.serverIpv6Entry)
        val remotePortEntry = view.findViewById<EditText>(R.id.serverRemotePortEntry)
        val localPortEntry = view.findViewById<EditText>(R.id.serverLocalPortEntry)
        val dnsEntry = view.findViewById<EditText>(R.id.serverDnsEntry)

        if (profile != null) {
            nameEntry.setText(profile.name)
            ipv6Entry.setText(profile.ipv6Address)
            remotePortEntry.setText(profile.remotePort)
            localPortEntry.setText(profile.localPort)
            dnsEntry.setText(profile.dnsServers)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (profile == null) R.string.server_add_title else R.string.server_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val updated = ServerProfile(
                    id = profile?.id ?: UUID.randomUUID().toString(),
                    name = nameEntry.text?.toString().orEmpty().ifBlank { getString(R.string.server_default_name) },
                    ipv6Address = ipv6Entry.text?.toString().orEmpty().trim(),
                    remotePort = remotePortEntry.text?.toString().orEmpty().trim(),
                    localPort = localPortEntry.text?.toString().orEmpty().trim(),
                    dnsServers = dnsEntry.text?.toString().orEmpty().trim()
                )
                val all = repository.getProfiles()
                val index = all.indexOfFirst { it.id == updated.id }
                if (index >= 0) {
                    all[index] = updated
                } else {
                    all.add(updated)
                }
                repository.saveProfiles(all)
                if (profile == null && repository.getActiveProfileId().isBlank()) {
                    repository.setActiveProfile(updated.id)
                }
                refreshData()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun deleteProfile(profile: ServerProfile) {
        val all = repository.getProfiles()
        if (all.size <= 1) {
            return
        }
        all.removeAll { it.id == profile.id }
        repository.saveProfiles(all)
        if (repository.getActiveProfileId() == profile.id) {
            repository.setActiveProfile(all.first().id)
        }
        refreshData()
    }

    private inner class ServersAdapter : BaseAdapter() {
        override fun getCount(): Int = profiles.size

        override fun getItem(position: Int): Any = profiles[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_server_profile, parent, false)
            val profile = profiles[position]

            val nameView = view.findViewById<TextView>(R.id.serverName)
            val detailsView = view.findViewById<TextView>(R.id.serverDetails)
            val activeBadge = view.findViewById<TextView>(R.id.serverActiveBadge)
            val editButton = view.findViewById<ImageButton>(R.id.serverEditButton)
            val deleteButton = view.findViewById<ImageButton>(R.id.serverDeleteButton)

            nameView.text = profile.name
            detailsView.text = "${profile.ipv6Address}:${profile.remotePort} • DNS: ${profile.dnsServers}"
            activeBadge.visibility = if (profile.id == activeProfileId) View.VISIBLE else View.GONE

            editButton.setOnClickListener { showAddOrEditDialog(profile) }
            deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(this@ServersActivity)
                    .setTitle(getString(R.string.server_delete_title, profile.name))
                    .setPositiveButton(R.string.remove) { dialog, _ ->
                        deleteProfile(profile)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            deleteButton.isEnabled = profiles.size > 1
            deleteButton.alpha = if (profiles.size > 1) 1.0f else 0.4f

            return view
        }
    }
}
