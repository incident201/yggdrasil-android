package eu.neilalexander.yggdrasil

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.CheckBox
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

data class PeerEntry(
    val uri: String,
    val country: String,
    var isSelected: Boolean = false,
    var isAlreadyAdded: Boolean = false
)

class PublicPeersActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var expandableListView: ExpandableListView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var addSelectedButton: MaterialButton

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var regionMap: LinkedHashMap<String, MutableList<PeerEntry>> = linkedMapOf()
    private var regionKeys: List<String> = emptyList()
    private var adapter: PeersExpandableAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_peers)

        config = ConfigurationProxy(applicationContext)

        val toolbar = findViewById<MaterialToolbar>(R.id.publicPeersToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        expandableListView = findViewById(R.id.publicPeersListView)
        progressBar = findViewById(R.id.publicPeersProgress)
        errorText = findViewById(R.id.publicPeersError)
        retryButton = findViewById(R.id.publicPeersRetry)
        addSelectedButton = findViewById(R.id.addSelectedButton)

        retryButton.setOnClickListener { loadPublicPeers() }
        addSelectedButton.setOnClickListener { addSelectedPeers() }

        loadPublicPeers()
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

    private fun loadPublicPeers() {
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        expandableListView.visibility = View.GONE
        addSelectedButton.visibility = View.GONE

        backgroundExecutor.execute {
            try {
                val peers = fetchPeersFromGitHub()
                val existingPeers = getExistingPeers()

                for ((_, peerList) in peers) {
                    for (peer in peerList) {
                        if (existingPeers.contains(peer.uri)) {
                            peer.isAlreadyAdded = true
                        }
                    }
                }

                mainHandler.post {
                    regionMap = peers
                    regionKeys = peers.keys.toList()
                    adapter = PeersExpandableAdapter()
                    expandableListView.setAdapter(adapter)

                    progressBar.visibility = View.GONE
                    expandableListView.visibility = View.VISIBLE
                    addSelectedButton.visibility = View.VISIBLE

                    // Expand all groups
                    for (i in regionKeys.indices) {
                        expandableListView.expandGroup(i)
                    }
                }
            } catch (e: Exception) {
                Log.e("PublicPeers", "Failed to load peers", e)
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    errorText.visibility = View.VISIBLE
                    errorText.text = getString(R.string.public_peers_error)
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getExistingPeers(): Set<String> {
        val json = config.getJSON()
        val peersArray = json.optJSONArray("Peers") ?: JSONArray()
        val set = mutableSetOf<String>()
        for (i in 0 until peersArray.length()) {
            set.add(peersArray.getString(i).trim())
        }
        return set
    }

    private fun fetchPeersFromGitHub(): LinkedHashMap<String, MutableList<PeerEntry>> {
        val result = linkedMapOf<String, MutableList<PeerEntry>>()

        // Fetch the list of directories (regions) from the GitHub API
        val apiUrl = "https://api.github.com/repos/yggdrasil-network/public-peers/contents"
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
        connection.disconnect()

        val jsonArray = JSONArray(response)
        val directories = mutableListOf<Pair<String, String>>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            if (item.getString("type") == "dir") {
                val name = item.getString("name")
                val downloadUrl = item.getString("url")
                directories.add(Pair(name, downloadUrl))
            }
        }

        // For each directory, fetch the .md files and parse peer URIs
        val peerPattern = Pattern.compile("((?:tcp|tls|quic|ws|wss)://[^\\s`]+)")

        for ((regionName, dirUrl) in directories) {
            val dirConnection = URL(dirUrl).openConnection() as HttpURLConnection
            dirConnection.requestMethod = "GET"
            dirConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            dirConnection.connectTimeout = 15000
            dirConnection.readTimeout = 15000

            val dirResponse = BufferedReader(InputStreamReader(dirConnection.inputStream)).readText()
            dirConnection.disconnect()

            val filesArray = JSONArray(dirResponse)
            val regionPeers = mutableListOf<PeerEntry>()

            for (j in 0 until filesArray.length()) {
                val file = filesArray.getJSONObject(j)
                val fileName = file.getString("name")
                if (fileName.endsWith(".md") && fileName != "README.md") {
                    val downloadUrl = file.optString("download_url", "")
                    if (downloadUrl.isNotEmpty()) {
                        try {
                            val fileConnection = URL(downloadUrl).openConnection() as HttpURLConnection
                            fileConnection.requestMethod = "GET"
                            fileConnection.connectTimeout = 10000
                            fileConnection.readTimeout = 10000
                            val fileContent = BufferedReader(InputStreamReader(fileConnection.inputStream)).readText()
                            fileConnection.disconnect()

                            val country = fileName.removeSuffix(".md").replace("-", " ")
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                            val matcher = peerPattern.matcher(fileContent)
                            while (matcher.find()) {
                                val uri = matcher.group(1)?.trim()?.trimEnd('`')
                                if (uri != null && uri.isNotEmpty()) {
                                    regionPeers.add(PeerEntry(uri, country))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("PublicPeers", "Failed to fetch $downloadUrl", e)
                        }
                    }
                }
            }

            if (regionPeers.isNotEmpty()) {
                result[regionName] = regionPeers
            }
        }

        return result
    }

    private fun addSelectedPeers() {
        var count = 0
        config.updateJSON { json ->
            val peersArray = json.optJSONArray("Peers") ?: JSONArray()
            if (!json.has("Peers")) {
                json.put("Peers", peersArray)
            }

            for ((_, peerList) in regionMap) {
                for (peer in peerList) {
                    if (peer.isSelected && !peer.isAlreadyAdded) {
                        peersArray.put(peer.uri)
                        peer.isAlreadyAdded = true
                        peer.isSelected = false
                        count++
                    }
                }
            }
        }

        if (count > 0) {
            Toast.makeText(this, getString(R.string.public_peers_added_count, count), Toast.LENGTH_SHORT).show()
            adapter?.notifyDataSetChanged()
        }
    }

    private inner class PeersExpandableAdapter : BaseExpandableListAdapter() {
        private val inflater = LayoutInflater.from(this@PublicPeersActivity)

        override fun getGroupCount(): Int = regionKeys.size

        override fun getChildrenCount(groupPosition: Int): Int {
            return regionMap[regionKeys[groupPosition]]?.size ?: 0
        }

        override fun getGroup(groupPosition: Int): String = regionKeys[groupPosition]

        override fun getChild(groupPosition: Int, childPosition: Int): PeerEntry {
            return regionMap[regionKeys[groupPosition]]!![childPosition]
        }

        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

        override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()

        override fun hasStableIds(): Boolean = false

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_public_peer_group, parent, false)
            val regionName = getGroup(groupPosition)
            val peerCount = getChildrenCount(groupPosition)
            view.findViewById<TextView>(R.id.regionName).text = regionName
            view.findViewById<TextView>(R.id.regionCount).text = "$peerCount"
            return view
        }

        override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_public_peer, parent, false)
            val peer = getChild(groupPosition, childPosition)
            val uriText = view.findViewById<TextView>(R.id.peerUri)
            val countryText = view.findViewById<TextView>(R.id.peerCountry)
            val checkBox = view.findViewById<CheckBox>(R.id.peerCheckBox)

            uriText.text = peer.uri
            countryText.text = peer.country

            if (peer.isAlreadyAdded) {
                checkBox.isChecked = true
                checkBox.isEnabled = false
                uriText.alpha = 0.5f
                countryText.text = "${peer.country} — ${getString(R.string.public_peers_already_added)}"
            } else {
                checkBox.isChecked = peer.isSelected
                checkBox.isEnabled = true
                uriText.alpha = 1.0f
            }

            view.setOnClickListener {
                if (!peer.isAlreadyAdded) {
                    peer.isSelected = !peer.isSelected
                    checkBox.isChecked = peer.isSelected
                }
            }

            checkBox.setOnClickListener {
                if (!peer.isAlreadyAdded) {
                    peer.isSelected = checkBox.isChecked
                }
            }

            return view
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
    }
}
