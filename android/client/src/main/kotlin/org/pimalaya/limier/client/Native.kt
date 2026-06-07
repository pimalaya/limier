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
