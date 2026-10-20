package com.teknoist.phaserprint.model

/**
 * Represents a discovered Xerox Phaser 3020 / Samsung M2020 printer on the local network or Wi-Fi Direct.
 */
data class DiscoveredPrinter(
    val name: String,
    val hostAddress: String,
    val port: Int = 9100,
    val connectionType: ConnectionType = ConnectionType.LAN,
    val model: String = "Xerox Phaser 3020"
) {
    enum class ConnectionType {
        LAN,
        WIFI_DIRECT,
        MANUAL_IP
    }
}
