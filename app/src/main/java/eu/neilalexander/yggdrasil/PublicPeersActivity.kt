package io.yggdrasilvpn

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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

// A flat list item for the RecyclerView — either a region header, country header, or a peer
sealed class PeerListItem {
    data class RegionHeader(val name: String, val totalPeers: Int) : PeerListItem()
    data class CountryHeader(val name: String, val peerCount: Int, val region: String) : PeerListItem()
    data class PeerItem(val peer: PeerEntry) : PeerListItem()
}

class PublicPeersActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var addSelectedButton: MaterialButton
    private lateinit var regionChipGroup: ChipGroup
    private lateinit var regionChipScroll: View

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // regionName -> (countryName -> List<PeerEntry>)
    private var regionMap: LinkedHashMap<String, LinkedHashMap<String, MutableList<PeerEntry>>> = linkedMapOf()
    private var flatItems: MutableList<PeerListItem> = mutableListOf()
    private var adapter: PeersRecyclerAdapter? = null

    // Collapsed state for regions and countries
    private val collapsedRegions = mutableSetOf<String>()
    private val collapsedCountries = mutableSetOf<String>() // key: "region/country"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_peers)

        config = ConfigurationProxy(applicationContext)

        val toolbar = findViewById<MaterialToolbar>(R.id.publicPeersToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.publicPeersListView)
        progressBar = findViewById(R.id.publicPeersProgress)
        errorText = findViewById(R.id.publicPeersError)
        retryButton = findViewById(R.id.publicPeersRetry)
        addSelectedButton = findViewById(R.id.addSelectedButton)
        regionChipGroup = findViewById(R.id.regionChipGroup)
        regionChipScroll = findViewById(R.id.regionChipScroll)

        recyclerView.layoutManager = LinearLayoutManager(this)

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
        recyclerView.visibility = View.GONE
        addSelectedButton.visibility = View.GONE
        regionChipGroup.visibility = View.GONE
        regionChipScroll.visibility = View.GONE

        backgroundExecutor.execute {
            try {
                val peers = fetchPeersFromGitHub()
                val existingPeers = getExistingPeers()

                for ((_, countryMap) in peers) {
                    for ((_, peerList) in countryMap) {
                        for (peer in peerList) {
                            if (existingPeers.contains(peer.uri)) {
                                peer.isAlreadyAdded = true
                            }
                        }
                    }
                }

                mainHandler.post {
                    regionMap = peers
                    rebuildFlatList()
                    setupRegionChips()

                    adapter = PeersRecyclerAdapter()
                    recyclerView.adapter = adapter

                    progressBar.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    addSelectedButton.visibility = View.VISIBLE
                    regionChipGroup.visibility = View.VISIBLE
                    regionChipScroll.visibility = View.VISIBLE
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

    private fun rebuildFlatList() {
        flatItems.clear()
        for ((regionName, countryMap) in regionMap) {
            val totalPeers = countryMap.values.sumOf { it.size }
            flatItems.add(PeerListItem.RegionHeader(regionName, totalPeers))

            if (regionName !in collapsedRegions) {
                for ((countryName, peerList) in countryMap) {
                    val key = "$regionName/$countryName"
                    flatItems.add(PeerListItem.CountryHeader(countryName, peerList.size, regionName))

                    if (key !in collapsedCountries) {
                        for (peer in peerList) {
                            flatItems.add(PeerListItem.PeerItem(peer))
                        }
                    }
                }
            }
        }
    }

    private fun setupRegionChips() {
        regionChipGroup.removeAllViews()

        // "All" chip
        val allChip = Chip(this)
        allChip.text = getString(R.string.public_peers_filter_all)
        allChip.isCheckable = true
        allChip.isChecked = true
        allChip.setOnClickListener {
            recyclerView.scrollToPosition(0)
        }
        regionChipGroup.addView(allChip)

        for (regionName in regionMap.keys) {
            val chip = Chip(this)
            chip.text = regionName.replaceFirstChar { it.uppercase() }
            chip.isCheckable = true
            chip.setOnClickListener {
                // Scroll to region
                val pos = flatItems.indexOfFirst {
                    it is PeerListItem.RegionHeader && it.name == regionName
                }
                if (pos >= 0) {
                    (recyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(pos, 0)
                }
            }
            regionChipGroup.addView(chip)
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

    private fun fetchPeersFromGitHub(): LinkedHashMap<String, LinkedHashMap<String, MutableList<PeerEntry>>> {
        val result = linkedMapOf<String, LinkedHashMap<String, MutableList<PeerEntry>>>()

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
            val countryMap = linkedMapOf<String, MutableList<PeerEntry>>()

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

                            val countryName = fileName.removeSuffix(".md")
                                .split("-")
                                .joinToString(" ") { word ->
                                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                }

                            val peers = mutableListOf<PeerEntry>()
                            val matcher = peerPattern.matcher(fileContent)
                            while (matcher.find()) {
                                val uri = matcher.group(1)?.trim()?.trimEnd('`')
                                if (uri != null && uri.isNotEmpty()) {
                                    peers.add(PeerEntry(uri, countryName))
                                }
                            }

                            if (peers.isNotEmpty()) {
                                countryMap[countryName] = peers
                            }
                        } catch (e: Exception) {
                            Log.w("PublicPeers", "Failed to fetch $downloadUrl", e)
                        }
                    }
                }
            }

            if (countryMap.isNotEmpty()) {
                result[regionName] = countryMap
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

            for ((_, countryMap) in regionMap) {
                for ((_, peerList) in countryMap) {
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
        }

        if (count > 0) {
            Toast.makeText(this, getString(R.string.public_peers_added_count, count), Toast.LENGTH_SHORT).show()
            rebuildFlatList()
            adapter?.notifyDataSetChanged()
        }
    }

    companion object {
        private const val VIEW_TYPE_REGION = 0
        private const val VIEW_TYPE_COUNTRY = 1
        private const val VIEW_TYPE_PEER = 2
    }

    private inner class PeersRecyclerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = when (flatItems[position]) {
            is PeerListItem.RegionHeader -> VIEW_TYPE_REGION
            is PeerListItem.CountryHeader -> VIEW_TYPE_COUNTRY
            is PeerListItem.PeerItem -> VIEW_TYPE_PEER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_REGION -> RegionViewHolder(
                    inflater.inflate(R.layout.item_peer_region_header, parent, false)
                )
                VIEW_TYPE_COUNTRY -> CountryViewHolder(
                    inflater.inflate(R.layout.item_peer_country_header, parent, false)
                )
                else -> PeerViewHolder(
                    inflater.inflate(R.layout.item_public_peer, parent, false)
                )
            }
        }

        override fun getItemCount(): Int = flatItems.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = flatItems[position]) {
                is PeerListItem.RegionHeader -> (holder as RegionViewHolder).bind(item)
                is PeerListItem.CountryHeader -> (holder as CountryViewHolder).bind(item)
                is PeerListItem.PeerItem -> (holder as PeerViewHolder).bind(item.peer)
            }
        }

        inner class RegionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val nameText: TextView = view.findViewById(R.id.regionName)
            private val countText: TextView = view.findViewById(R.id.regionCount)
            private val arrowView: TextView = view.findViewById(R.id.regionArrow)

            fun bind(item: PeerListItem.RegionHeader) {
                nameText.text = item.name.replaceFirstChar { it.uppercase() }
                countText.text = item.totalPeers.toString()
                val collapsed = item.name in collapsedRegions
                arrowView.text = if (collapsed) "▶" else "▼"

                itemView.setOnClickListener {
                    if (item.name in collapsedRegions) {
                        collapsedRegions.remove(item.name)
                    } else {
                        collapsedRegions.add(item.name)
                    }
                    rebuildFlatList()
                    notifyDataSetChanged()
                }
            }
        }

        inner class CountryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val nameText: TextView = view.findViewById(R.id.countryName)
            private val countText: TextView = view.findViewById(R.id.countryCount)
            private val arrowView: TextView = view.findViewById(R.id.countryArrow)

            fun bind(item: PeerListItem.CountryHeader) {
                nameText.text = item.name
                countText.text = item.peerCount.toString()
                val key = "${item.region}/${item.name}"
                val collapsed = key in collapsedCountries
                arrowView.text = if (collapsed) "▶" else "▼"

                itemView.setOnClickListener {
                    if (key in collapsedCountries) {
                        collapsedCountries.remove(key)
                    } else {
                        collapsedCountries.add(key)
                    }
                    rebuildFlatList()
                    notifyDataSetChanged()
                }
            }
        }

        inner class PeerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val uriText: TextView = view.findViewById(R.id.peerUri)
            private val countryText: TextView = view.findViewById(R.id.peerCountry)
            private val checkBox: CheckBox = view.findViewById(R.id.peerCheckBox)

            fun bind(peer: PeerEntry) {
                uriText.text = peer.uri

                if (peer.isAlreadyAdded) {
                    countryText.text = getString(R.string.public_peers_already_added)
                    countryText.visibility = View.VISIBLE
                    checkBox.isChecked = true
                    checkBox.isEnabled = false
                    uriText.alpha = 0.5f
                } else {
                    countryText.visibility = View.GONE
                    checkBox.isChecked = peer.isSelected
                    checkBox.isEnabled = true
                    uriText.alpha = 1.0f
                }

                itemView.setOnClickListener {
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
    }
}
