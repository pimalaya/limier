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
 * The liblimier.so boundary. Implemented in Rust as
 * Java_org_pimalaya_limier_client_Native_search; the bridge drives
 * io-imap's coroutines and does socket I/O through [transport].
 *
 * Returns a JSON string: an array of mailbox hits, or `{"error": ".."}`.
 */
internal object Native {
    init {
        System.loadLibrary("limier")
    }

    @JvmStatic
    external fun search(
        transport: Transport,
        login: String,
        password: String,
        sasl: String,
        keywords: String,
    ): String
}
