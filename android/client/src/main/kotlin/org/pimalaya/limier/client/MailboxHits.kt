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

/** Matching messages found in a single mailbox. */
data class MailboxHits(
    val mailbox: String,
    val hits: List<Hit>,
)

/**
 * One matching message. [timestamp] is the Date header as Unix seconds
 * (0 when unparseable); the UI formats it locally and falls back to the
 * raw [date].
 */
data class Hit(
    val uid: Long,
    val subject: String,
    val date: String,
    val timestamp: Long,
)
