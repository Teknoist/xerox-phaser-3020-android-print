package com.teknoist.phaserprint.discovery

import android.content.Context
import android.util.Log
import com.teknoist.phaserprint.model.DiscoveredPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-tier discovery aggregator:
 * Level 1: mDNS / DNS-SD
 * Level 2: Fast Port 9100 Subnet Prober
 * Level 3: Wi-Fi Direct (P2P) Fallback
 */
class PrinterDiscoveryAggregator(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryAggregator"
        private const val PREFS_NAME = "phaser_printer_prefs"
        private const val KEY_LAST_KNOWN_IP = "last_known_ip"
    }

    private val nsdDiscovery = NsdPrinterDiscovery(context)
    private val subnetProber = SubnetPortProber(context)
    private val wifiDirectDiscovery = WifiDirectFallbackDiscovery(context)

    suspend fun discoverPrinter(
        onStatusUpdate: (status: String) -> Unit
    ): DiscoveredPrinter? = withContext(Dispatchers.IO) {
        // Check last known cached IP first for instant connection (<50ms)
        val lastIp = getCachedIp()
        if (lastIp != null) {
            onStatusUpdate("Checking last known printer at $lastIp:9100...")
            if (isHostReachable(lastIp)) {
                Log.i(TAG, "Reused last known IP: $lastIp")
                return@withContext DiscoveredPrinter(
                    name = "Xerox Phaser 3020 ($lastIp)",
                    hostAddress = lastIp,
                    port = 9100,
                    connectionType = DiscoveredPrinter.ConnectionType.LAN
                )
            }
        }

        // Tier 1: mDNS
        onStatusUpdate("Scanning local network via mDNS...")
        val mdnsPrinter = nsdDiscovery.discover(timeoutMs = 2500)
        if (mdnsPrinter != null) {
            saveCachedIp(mdnsPrinter.hostAddress)
            return@withContext mdnsPrinter
        }

        // Tier 2: Fast Subnet Port 9100 Probe
        onStatusUpdate("Probing local subnet on Port 9100...")
        val subnetPrinter = subnetProber.probeSubnet()
        if (subnetPrinter != null) {
            saveCachedIp(subnetPrinter.hostAddress)
            return@withContext subnetPrinter
        }

        // Tier 3: Wi-Fi Direct Fallback
        onStatusUpdate("Searching for Wi-Fi Direct: DIRECT-xx-Phaser 3020...")
        val directPrinter = wifiDirectDiscovery.discoverAndConnect(timeoutMs = 6000)
        if (directPrinter != null) {
            saveCachedIp(directPrinter.hostAddress)
            return@withContext directPrinter
        }

        null
    }

    private fun isHostReachable(ip: String): Boolean {
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress(ip, 9100), 200)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getCachedIp(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_KNOWN_IP, null)
    }

    private fun saveCachedIp(ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_KNOWN_IP, ip).apply()
    }
}
