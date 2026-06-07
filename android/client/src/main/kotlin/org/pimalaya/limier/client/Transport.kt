// limier - search your mailboxes for lost mail
// Copyright (C) 2026  Clement DOUIN
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public
// License along with this program. If not, see
// <https://www.gnu.org/licenses/>.

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
