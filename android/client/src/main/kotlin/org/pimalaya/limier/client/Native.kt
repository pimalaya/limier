package org.pimalaya.limier.client

/**
 * The liblimier.so boundary. Each call owns one [Transport] connection,
 * so the client runs several in parallel.
 *
 * - [listMailboxes] returns a JSON array of selectable mailbox names,
 *   or `{"error": ".."}`.
 * - [searchMailboxes] drives [sink] (onMailbox / onProgress / shouldStop)
 *   mailbox by mailbox, and returns an empty string on success or an
 *   error message.
 * - [fetchMessage] returns one message's MIME parts as
 *   `{"parts": [..]}`, or `{"error": ".."}`.
 */
internal object Native {
    init {
        System.loadLibrary("limier")
    }

    @JvmStatic
    external fun listMailboxes(
        transport: Transport,
        login: String,
        password: String,
        sasl: String,
    ): String

    @JvmStatic
    external fun searchMailboxes(
        transport: Transport,
        login: String,
        password: String,
        sasl: String,
        mailboxes: String,
        keywords: String,
        sink: NativeSink,
    ): String

    @JvmStatic
    external fun fetchMessage(
        transport: Transport,
        login: String,
        password: String,
        sasl: String,
        mailbox: String,
        uid: Long,
    ): String
}
