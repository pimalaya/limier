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

import android.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/** Raised when the IMAP session itself fails (connection, auth, ...). */
class ImapException(message: String) : Exception(message)

/** Returned by [ImapClient.search]; [cancel] stops the running search. */
fun interface SearchHandle {
    fun cancel()
}

/**
 * Streamed search results. Callbacks arrive on background worker
 * threads (several mailboxes may complete at once), so implementations
 * must marshal to their UI thread themselves.
 */
interface SearchListener {
    /** One mailbox's hits, as soon as that mailbox finishes. */
    fun onMailbox(hits: MailboxHits)

    /** [done] of [total] mailboxes processed (hits or not). */
    fun onProgress(done: Int, total: Int)

    /** Called once when the search ends; [error] is null on success or cancel. */
    fun onFinished(error: String?)
}

/**
 * The only surface the app needs. [search] returns immediately and runs
 * asynchronously: it lists the mailboxes once, then fans the search out
 * across several TLS connections in parallel, forwarding each mailbox's
 * hits through [SearchListener] as it arrives. Owns all sockets and the
 * Rust bridge; the app sees neither.
 */
class ImapClient {
    private val coordinators = Executors.newCachedThreadPool()

    /** Starts a search and returns a handle to cancel it. */
    fun search(account: Account, keywords: String, listener: SearchListener): SearchHandle {
        val cancelled = AtomicBoolean(false)
        coordinators.execute { runSearch(account, keywords, listener, cancelled) }
        return SearchHandle { cancelled.set(true) }
    }

    private fun runSearch(
        account: Account,
        keywords: String,
        listener: SearchListener,
        cancelled: AtomicBoolean,
    ) {
        val mailboxes =
            try {
                listMailboxes(account)
            } catch (error: Exception) {
                listener.onFinished(error.message ?: "Listing mailboxes failed")
                return
            }

        if (cancelled.get() || mailboxes.isEmpty()) {
            listener.onFinished(null)
            return
        }

        listener.onProgress(0, mailboxes.size)

        val workerCount = minOf(WORKERS, mailboxes.size)
        val buckets =
            List(workerCount) { worker ->
                mailboxes.filterIndexed { index, _ -> index % workerCount == worker }
            }

        val pool = Executors.newFixedThreadPool(workerCount)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val done = AtomicInteger(0)

        try {
            buckets
                .map { bucket ->
                    pool.submit {
                        try {
                            searchBucket(
                                account,
                                bucket,
                                keywords,
                                cancelled,
                                onHits = { hits -> listener.onMailbox(hits) },
                                onAdvance = {
                                    listener.onProgress(done.incrementAndGet(), mailboxes.size)
                                },
                            )
                        } catch (error: Exception) {
                            errors.add(error.message ?: "Worker failed")
                        }
                    }
                }
                .forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        listener.onFinished(if (cancelled.get()) null else errors.firstOrNull())
    }

    /**
     * Connects, authenticates and lists once to prove the account is
     * usable. Blocking: call off the main thread. Throws on failure.
     */
    fun verify(account: Account) {
        listMailboxes(account)
    }

    /**
     * Fetches and parses one message into its MIME parts. Blocking:
     * call off the main thread.
     */
    fun fetchMessage(account: Account, mailbox: String, uid: Long): List<MessagePart> =
        withConnection(account) { transport ->
            val json =
                Native.fetchMessage(
                    transport,
                    account.login,
                    account.password,
                    account.sasl.name,
                    mailbox,
                    uid,
                )
            parseParts(json)
        }

    private fun parseParts(json: String): List<MessagePart> {
        val obj = JSONObject(json.trim())

        val error = obj.optString("error")
        if (error.isNotEmpty()) {
            throw ImapException(error)
        }

        val parts = obj.getJSONArray("parts")
        return (0 until parts.length()).map { index ->
            val part = parts.getJSONObject(index)
            val kind =
                when (part.getString("kind")) {
                    "text" -> PartKind.TEXT
                    "image" -> PartKind.IMAGE
                    else -> PartKind.BINARY
                }

            MessagePart(
                mime = part.getString("mime"),
                kind = kind,
                text = if (part.has("text")) part.getString("text") else null,
                data =
                    if (part.has("data")) {
                        Base64.decode(part.getString("data"), Base64.DEFAULT)
                    } else {
                        null
                    },
                filename = if (part.has("filename")) part.getString("filename") else null,
            )
        }
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
        cancelled: AtomicBoolean,
        onHits: (MailboxHits) -> Unit,
        onAdvance: () -> Unit,
    ) {
        if (mailboxes.isEmpty()) {
            return
        }

        withConnection(account) { transport ->
            val sink = NativeSink(cancelled, onHits, onAdvance)
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
