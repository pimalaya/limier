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

import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/** Raised when the IMAP session itself fails (connection, auth, ...). */
class ImapException(message: String) : Exception(message)

/**
 * Streamed search results. Callbacks arrive on background worker
 * threads (several mailboxes may complete at once), so implementations
 * must marshal to their UI thread themselves.
 */
interface SearchListener {
    /** One mailbox's hits, as soon as that mailbox finishes. */
    fun onMailbox(hits: MailboxHits)

    /** Called once when the whole search ends; [error] is null on success. */
    fun onFinished(error: String?)
}

/**
 * The only surface the app needs. [search] lists the mailboxes once,
 * then fans the search out across several TLS connections in parallel,
 * forwarding each mailbox's hits through [SearchListener] as it arrives.
 * Owns all sockets and the Rust bridge; the app sees neither.
 *
 * Blocking: call [search] off the main thread.
 */
class ImapClient {
    fun search(account: Account, keywords: String, listener: SearchListener) {
        val mailboxes =
            try {
                listMailboxes(account)
            } catch (error: Exception) {
                listener.onFinished(error.message ?: "Listing mailboxes failed")
                return
            }

        if (mailboxes.isEmpty()) {
            listener.onFinished(null)
            return
        }

        val workerCount = minOf(WORKERS, mailboxes.size)
        val buckets =
            List(workerCount) { worker ->
                mailboxes.filterIndexed { index, _ -> index % workerCount == worker }
            }

        val pool = Executors.newFixedThreadPool(workerCount)
        val errors = Collections.synchronizedList(mutableListOf<String>())

        try {
            buckets
                .map { bucket ->
                    pool.submit {
                        try {
                            searchBucket(account, bucket, keywords, listener)
                        } catch (error: Exception) {
                            errors.add(error.message ?: "Worker failed")
                        }
                    }
                }
                .forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        listener.onFinished(errors.firstOrNull())
    }

    private fun listMailboxes(account: Account): List<String> =
        withConnection(account) { transport ->
            val json =
                Native.listMailboxes(transport, account.login, account.password, account.sasl.name)
            val trimmed = json.trim()

            if (trimmed.startsWith("{")) {
                val error = JSONObject(trimmed).optString("error")
                if (error.isNotEmpty()) {
                    throw ImapException(error)
                }
            }

            val names = JSONArray(trimmed)
            (0 until names.length()).map { names.getString(it) }
        }

    private fun searchBucket(
        account: Account,
        mailboxes: List<String>,
        keywords: String,
        listener: SearchListener,
    ) {
        if (mailboxes.isEmpty()) {
            return
        }

        withConnection(account) { transport ->
            val sink = MailboxSink { hits -> listener.onMailbox(hits) }
            val error =
                Native.searchMailboxes(
                    transport,
                    account.login,
                    account.password,
                    account.sasl.name,
                    JSONArray(mailboxes).toString(),
                    keywords,
                    sink,
                )
            if (error.isNotEmpty()) {
                throw ImapException(error)
            }
        }
    }

    private fun <T> withConnection(account: Account, block: (Transport) -> T): T {
        val socket =
            SSLSocketFactory.getDefault().createSocket(account.domain, account.port) as SSLSocket

        socket.use { connected ->
            connected.soTimeout = SOCKET_TIMEOUT_MS
            connected.startHandshake()
            return block(Transport(connected))
        }
    }

    private companion object {
        const val WORKERS = 4
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
