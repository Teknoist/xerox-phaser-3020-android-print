package com.teknoist.phaserprint.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.teknoist.phaserprint.model.DiscoveredPrinter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Discovers network printers using Android's Network Service Discovery (mDNS / DNS-SD).
 * Service types: _pdl-datastream._tcp, _ipp._tcp
 */
class NsdPrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "NsdPrinterDiscovery"
        private const val SERVICE_TYPE_PDL = "_pdl-datastream._tcp."
        private const val SERVICE_TYPE_IPP = "_ipp._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    suspend fun discover(timeoutMs: Long = 4000): DiscoveredPrinter? {
        if (nsdManager == null) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var resolved = false

                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo?.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        val name = serviceInfo.serviceName

                        Log.d(TAG, "Resolved mDNS service: $name at $host:$port")

                        val isMatch = isPhaser3020Match(name, serviceInfo)
                        if (isMatch && host != null && !resolved) {
                            resolved = true
                            continuation.resume(
                                DiscoveredPrinter(
                                    name = name,
                                    hostAddress = host,
                                    port = if (port > 0) port else 9100,
                                    connectionType = DiscoveredPrinter.ConnectionType.LAN
                                )
                            )
                        }
                    }
                }

                val discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        Log.e(TAG, "Start discovery failed: $errorCode")
                        if (!resolved) {
                            resolved = true
                            continuation.resume(null)
                        }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        Log.e(TAG, "Stop discovery failed: $errorCode")
                    }

                    override fun onDiscoveryStarted(serviceType: String?) {
                        Log.d(TAG, "mDNS discovery started for $serviceType")
                    }

                    override fun onDiscoveryStopped(serviceType: String?) {
                        Log.d(TAG, "mDNS discovery stopped for $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service found: ${serviceInfo.serviceName} type ${serviceInfo.serviceType}")
                        try {
                            nsdManager.resolveService(serviceInfo, resolveListener)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to resolve service: ${e.message}")
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                        Log.d(TAG, "Service lost: ${serviceInfo?.serviceName}")
                    }
                }

                try {
                    nsdManager.discoverServices(SERVICE_TYPE_PDL, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate mDNS discovery", e)
                    if (!resolved) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun isPhaser3020Match(serviceName: String, info: NsdServiceInfo): Boolean {
        val lower = serviceName.lowercase()
        if (lower.contains("3020") || lower.contains("phaser") || lower.contains("m2020")) {
            return true
        }

        // Check TXT records if available
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val attributes = info.attributes
            for ((key, valueBytes) in attributes) {
                val value = String(valueBytes, Charsets.UTF_8).lowercase()
                if (value.contains("3020") || value.contains("phaser") || value.contains("m2020") || value.contains("xerox")) {
                    return true
                }
            }
        }

        // Generic fallback: if it's a pdl-datastream printer on the network
        return lower.contains("xerox") || lower.contains("samsung")
    }
}
