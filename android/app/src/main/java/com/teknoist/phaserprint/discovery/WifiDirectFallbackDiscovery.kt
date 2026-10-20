package com.teknoist.phaserprint.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.teknoist.phaserprint.model.DiscoveredPrinter
import com.teknoist.phaserprint.protocol.SplConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct (P2P) Fallback Discovery.
 * Discovers Xerox Phaser 3020 / Samsung M2020 Wi-Fi Direct broadcasts (SSID DIRECT-xx-Phaser 3020),
 * initiates connection, and resolves the printer gateway IP (192.168.49.1 / 192.168.32.1).
 */
class WifiDirectFallbackDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectDiscovery"
    }

    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = p2pManager?.initialize(context, Looper.getMainLooper(), null)

    @SuppressLint("MissingPermission")
    suspend fun discoverAndConnect(timeoutMs: Long = 8000): DiscoveredPrinter? = withContext(Dispatchers.Main) {
        if (p2pManager == null || channel == null) {
            Log.w(TAG, "Wi-Fi Direct not supported on this device.")
            return@withContext null
        }

        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var matchedDevice: WifiP2pDevice? = null
                var finished = false

                val peerListListener = WifiP2pManager.PeerListListener { peerList: WifiP2pDeviceList? ->
                    if (finished) return@PeerListListener
                    val devices = peerList?.deviceList ?: return@PeerListListener
                    Log.d(TAG, "Found ${devices.size} Wi-Fi Direct peers")

                    for (device in devices) {
                        val name = device.deviceName ?: ""
                        Log.d(TAG, "Inspecting peer: $name (${device.deviceAddress})")
                        if (isPhaserDirectSSID(name)) {
                            matchedDevice = device
                            Log.i(TAG, "Matched Xerox/Samsung Wi-Fi Direct printer: $name")
                            break
                        }
                    }

                    val target = matchedDevice
                    if (target != null && !finished) {
                        val config = WifiP2pConfig().apply {
                            deviceAddress = target.deviceAddress
                        }
                        p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Log.i(TAG, "Wi-Fi Direct connection initiated to ${target.deviceName}")
                                p2pManager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                                    if (finished) return@requestConnectionInfo
                                    finished = true
                                    val groupOwner = info?.groupOwnerAddress?.hostAddress
                                    val printerIp = groupOwner ?: SplConstants.WIFI_DIRECT_DEFAULT_IPS.first()
                                    continuation.resume(
                                        DiscoveredPrinter(
                                            name = target.deviceName ?: "Xerox Phaser 3020 (Direct)",
                                            hostAddress = printerIp,
                                            port = SplConstants.RAW_PRINT_PORT,
                                            connectionType = DiscoveredPrinter.ConnectionType.WIFI_DIRECT
                                        )
                                    )
                                }
                            }

                            override fun onFailure(reason: Int) {
                                Log.e(TAG, "Wi-Fi Direct connect failed: $reason")
                                if (!finished) {
                                    finished = true
                                    // Fallback to default direct IP if association was already made
                                    continuation.resume(
                                        DiscoveredPrinter(
                                            name = target.deviceName ?: "Xerox Phaser 3020 (Direct)",
                                            hostAddress = SplConstants.WIFI_DIRECT_DEFAULT_IPS.first(),
                                            port = SplConstants.RAW_PRINT_PORT,
                                            connectionType = DiscoveredPrinter.ConnectionType.WIFI_DIRECT
                                        )
                                    )
                                }
                            }
                        })
                    }
                }

                p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Peer discovery started successfully.")
                        p2pManager.requestPeers(channel, peerListListener)
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Peer discovery failed: $reason")
                        if (!finished) {
                            finished = true
                            continuation.resume(null)
                        }
                    }
                })

                continuation.invokeOnCancellation {
                    try {
                        p2pManager.stopPeerDiscovery(channel, null)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun isPhaserDirectSSID(name: String): Boolean {
        val upper = name.uppercase()
        return upper.contains("PHASER") ||
                upper.contains("3020") ||
                upper.contains("M2020") ||
                upper.contains("SCX-3020") ||
                (upper.startsWith("DIRECT-") && (upper.contains("XEROX") || upper.contains("SEC") || upper.contains("SAMSUNG")))
    }
}
