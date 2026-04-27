package io.yggdrasilvpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import io.yggdrasilvpn.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


private const val TAG = "PacketTunnelProvider"
const val SERVICE_NOTIFICATION_ID = 1000

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "io.yggdrasilvpn.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "io.yggdrasilvpn.PacketTunnelProvider.START"
        const val ACTION_STOP = "io.yggdrasilvpn.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "io.yggdrasilvpn.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "io.yggdrasilvpn.PacketTunnelProvider.CONNECT"
    }

    private var yggdrasil = Yggdrasil()
    private var started = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var exitModeEnabled = false

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                // Clear the enabled flag so MainActivity doesn't re-start VPN on next open
                PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                    .edit()
                    .putBoolean(PREF_KEY_ENABLED, false)
                    .apply()
                stop(); START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                        .edit().putBoolean(PREF_KEY_ENABLED, false).apply()
                    stop(); START_NOT_STICKY
                } else {
                    PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                        .edit().putBoolean(PREF_KEY_ENABLED, true).apply()
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(SERVICE_NOTIFICATION_ID, notification)

        // Acquire multicast lock
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.d(TAG, config.getJSON().toString())
        yggdrasil.startJSON(config.getJSONByteArray())

        val appPreferences = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
        exitModeEnabled = appPreferences.getBoolean(PREF_KEY_EXIT_MODE, false)
        parcel = if (exitModeEnabled) {
            startExitMode(appPreferences)
        } else {
            startNormalMode()
        }
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stop()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread { reader() }
        writerThread = thread { writer() }
        updateThread = thread {
            updater()
        }

        var intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        if (exitModeEnabled) {
            try {
                yggdrasil.closeTunnel()
            } catch (e: Exception) {
                Log.w(TAG, "Error while closing tunnel", e)
            }
        }
        yggdrasil.stop()
        exitModeEnabled = false

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(true)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.retryPeersNow()
    }

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as  GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                var state = STATE_ENABLED
                if (yggdrasil.routingEntries > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    val treeState = JSONArray(treeJSON)
                    val count = treeState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        if (exitModeEnabled) {
            exitWriter()
            return
        }
        normalWriter()
    }

    private fun normalWriter() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    //TODO Check this by some error code
                    //More info about this: https://github.com/AdguardTeam/AdguardForAndroid/issues/724
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun exitWriter() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvTunnelBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in recvTunnelBuffer: $e")
                if (e.toString().contains("ENOBUFS")) {
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun reader() {
        if (exitModeEnabled) {
            exitReader()
            return
        }
        normalReader()
    }

    private fun normalReader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted ||!readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }

    private fun exitReader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted || !readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                if (n <= 0) {
                    continue
                }
                try {
                    yggdrasil.sendTunnelBuffer(b, n.toLong())
                } catch (e: Exception) {
                    Log.i(TAG, "Error in sendTunnelBuffer: $e")
                    break@reads
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in exit reader: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }

    private fun startNormalMode(): ParcelFileDescriptor? {
        val address = yggdrasil.addressString
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            // We do this to trick the DNS-resolver into thinking that we have "regular" IPv6,
            // and therefore we need to resolve AAAA DNS-records.
            // See: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#1935
            // and: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#365
            // If we don't do this the DNS-resolver just doesn't do DNS-requests with record type AAAA,
            // and we can't use DNS with Yggdrasil addresses.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")
        // On Android API 29+ apps can opt-in/out to using metered networks.
        // If we don't set metered status of VPN it is considered as metered.
        // If we set it to false, then it will inherit this status from underlying network.
        // See: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }
        return builder.establish()
    }

    private fun startExitMode(preferences: SharedPreferences): ParcelFileDescriptor? {
        val activeConfig = ExitVpnConfigStore.getActive(preferences)
        val innerIp = activeConfig.innerIp.trim()
        val remoteAddr = activeConfig.remoteAddr.trim()
        val remotePort = activeConfig.remotePort.trim().toLongOrNull()
        val localPort = activeConfig.localPort.trim().toLongOrNull()
        if (remoteAddr.isEmpty() || remotePort == null || localPort == null || !isAllowedExitInnerIp(innerIp)) {
            Log.e(TAG, "Exit mode is enabled but remote address/ports/inner IP are not configured")
            return null
        }
        if (remotePort <= 0 || localPort <= 0) {
            Log.e(TAG, "Exit mode remote/local ports must be positive values")
            return null
        }

        val effectiveMtu = 1280

        val builder = Builder()
            .addAddress(innerIp, 24)
            .addRoute("0.0.0.0", 0)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(effectiveMtu)
            .setSession("Yggdrasil Exit")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val dnsServers = listOf(activeConfig.dnsServer1, activeConfig.dnsServer2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val servers = if (dnsServers.isNotEmpty()) dnsServers else listOf("1.1.1.1")
        servers.forEach {
            val dnsServer = it.trim()
            if (dnsServer.isNotEmpty()) {
                builder.addDnsServer(dnsServer)
            }
        }

        // Always exclude our own app from the VPN tunnel
        try {
            builder.addDisallowedApplication(applicationContext.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to self-exclude app", e)
        }

        preferences.getString(PREF_KEY_EXIT_EXCLUDED_APPS, "").orEmpty()
            .split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != applicationContext.packageName }
            ?.forEach { packageName ->
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude app $packageName", e)
                }
            }

        try {
            yggdrasil.openTunnel(remoteAddr, remotePort, localPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open exit tunnel", e)
            return null
        }

        return builder.establish()
    }

    private fun isAllowedExitInnerIp(innerIp: String): Boolean {
        val address = try {
            InetAddress.getByName(innerIp)
        } catch (_: Exception) {
            null
        } as? Inet4Address ?: return false
        val bytes = address.address
        val fourth = bytes[3].toInt() and 0xFF
        return fourth != 0 && fourth != 1 && fourth != 255
    }
}
