package com.teknoist.phaserprint.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.teknoist.phaserprint.model.DiscoveredPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Fast Subnet Port 9100 Scanner.
 * When mDNS/NSD multicast is filtered or blocked by Wi-Fi routers (AP isolation),
 * this scans the local /24 subnet in parallel with low latency (200ms) sockets.
 */
class SubnetPortProber(private val context: Context) {

    companion object {
        private const val TAG = "SubnetPortProber"
        private const val PORT_9100 = 9100
        private const val PROBE_TIMEOUT_MS = 250
    }

    suspend fun probeSubnet(): DiscoveredPrinter? = withContext(Dispatchers.IO) {
        val baseIp = getLocalSubnetPrefix()
        if (baseIp == null) {
            Log.w(TAG, "Cannot determine local subnet IP prefix.")
            return@withContext null
        }

        Log.d(TAG, "Starting fast parallel subnet probe on $baseIp.0/24...")

        // Scan IPs 1 through 254 concurrently
        coroutineScope {
            val deferreds = (1..254).map { hostNum ->
                async {
                    val targetIp = "$baseIp.$hostNum"
                    probeHost(targetIp)
                }
            }

            for (deferred in deferreds) {
                val printer = deferred.await()
                if (printer != null) {
                    Log.i(TAG, "Subnet probe found active printer at ${printer.hostAddress}")
                    return@coroutineScope printer
                }
            }
            null
        }
    }

    private fun probeHost(ip: String): DiscoveredPrinter? {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, PORT_9100), PROBE_TIMEOUT_MS)
            socket.soTimeout = 400

            // Query printer identity via PJL
            val out = socket.getOutputStream()
            out.write("\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val buffer = ByteArray(512)
            val inputStream: InputStream = socket.getInputStream()
            val read = try {
                inputStream.read(buffer)
            } catch (_: Exception) { -1 }

            val response = if (read > 0) String(buffer, 0, read) else ""
            val name = if (response.contains("3020") || response.contains("Phaser")) {
                "Xerox Phaser 3020 ($ip)"
            } else if (response.contains("M2020") || response.contains("Samsung")) {
                "Samsung M2020 ($ip)"
            } else {
                "Xerox Phaser 3020 / Port 9100 ($ip)"
            }

            return DiscoveredPrinter(
                name = name,
                hostAddress = ip,
                port = PORT_9100,
                connectionType = DiscoveredPrinter.ConnectionType.LAN
            )
        } catch (_: Exception) {
            return null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getLocalSubnetPrefix(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                val ip = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d",
                    ipAddress and 0xFF,
                    (ipAddress shr 8) and 0xFF,
                    (ipAddress shr 16) and 0xFF
                )
                if (ip != "0.0.0") return ip
            }

            // Fallback: inspect NetworkInterface
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && host.startsWith("192.168.") || host != null && host.startsWith("10.")) {
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering subnet", e)
        }
        return null
    }
}
