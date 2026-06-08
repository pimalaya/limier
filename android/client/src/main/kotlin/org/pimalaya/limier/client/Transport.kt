package org.pimalaya.limier.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import javax.net.ssl.SSLSocket

/**
 * Socket I/O for the Rust bridge. The native coroutine driver calls
 * [read] / [write] by name on every WantsRead / WantsWrite yield, so
 * both stay public (no Kotlin name mangling) for JNI lookup.
 */
internal class Transport(socket: SSLSocket) {
    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)
    private val buffer = ByteArray(16 * 1024)

    /** Reads the next chunk; an empty array signals EOF to the bridge. */
    fun read(): ByteArray {
        val read = input.read(buffer)
        return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    /** Writes and flushes every byte the bridge hands over. */
    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}
