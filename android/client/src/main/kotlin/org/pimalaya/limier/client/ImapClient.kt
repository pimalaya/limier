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

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/** Raised when the IMAP session itself fails (connection, auth, ...). */
class ImapException(message: String) : Exception(message)

/**
 * The only surface the app needs: hand it an [Account] and keywords,
 * get back matching messages grouped by mailbox. Owns the TLS socket
 * and the Rust bridge; the app never touches sockets or JNI.
 *
 * Blocking and network-bound: call it off the main thread.
 */
class ImapClient {
    /**
     * Opens an implicit-TLS connection (port 993 by convention),
     * authenticates, and runs one broad UID SEARCH across every
     * selectable mailbox. [keywords] are whitespace-split and OR-ed
     * over the whole message (header and body).
     */
    fun searchAll(account: Account, keywords: String): List<MailboxHits> {
        val socket =
            SSLSocketFactory.getDefault().createSocket(account.domain, account.port) as SSLSocket

        socket.use { connected ->
            connected.soTimeout = SOCKET_TIMEOUT_MS
            connected.startHandshake()

            val json =
                Native.search(
                    Transport(connected),
                    account.login,
                    account.password,
                    account.sasl.name,
                    keywords,
                )

            return parse(json)
        }
    }

    /** Turns the bridge's JSON reply into typed results, or throws. */
    private fun parse(json: String): List<MailboxHits> {
        val trimmed = json.trim()

        if (trimmed.startsWith("{")) {
            val error = JSONObject(trimmed).optString("error")
            if (error.isNotEmpty()) {
                throw ImapException(error)
            }
        }

        val mailboxes = JSONArray(trimmed)
        return (0 until mailboxes.length()).map { mailboxIndex ->
            val mailbox = mailboxes.getJSONObject(mailboxIndex)
            val hits = mailbox.getJSONArray("hits")

            MailboxHits(
                mailbox = mailbox.getString("mailbox"),
                hits =
                    (0 until hits.length()).map { hitIndex ->
                        val hit = hits.getJSONObject(hitIndex)
                        Hit(
                            uid = hit.getLong("uid"),
                            subject = hit.getString("subject"),
                            date = hit.getString("date"),
                        )
                    },
            )
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
