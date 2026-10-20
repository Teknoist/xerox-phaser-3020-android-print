package com.teknoist.phaserprint.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * High-performance raw TCP stream client for AppSocket / JetDirect Port 9100.
 */
class RawPort9100Client(
    val connectTimeoutMs: Int = 5000,
    val socketTimeoutMs: Int = 30000
) {

    /**
     * Streams an SPL payload from an InputStream or ByteArray directly to the printer at host:port.
     */
    suspend fun sendStream(
        host: String,
        port: Int = 9100,
        streamSupplier: (OutputStream) -> Unit,
        onProgress: (bytesSent: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = socketTimeoutMs

        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val socketOut = socket.getOutputStream()

            // Custom counting stream for real-time progress callbacks
            val countingOut = object : OutputStream() {
                var totalBytesSent: Long = 0

                override fun write(b: Int) {
                    socketOut.write(b)
                    totalBytesSent++
                    if (totalBytesSent % 16384L == 0L) {
                        onProgress(totalBytesSent)
                    }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    socketOut.write(b, off, len)
                    totalBytesSent += len
                    onProgress(totalBytesSent)
                }

                override fun flush() {
                    socketOut.flush()
                }

                override fun close() {
                    socketOut.close()
                }
            }

            streamSupplier(countingOut)
            countingOut.flush()
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
