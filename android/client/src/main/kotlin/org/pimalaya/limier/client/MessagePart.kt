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

/** How a [MessagePart] should be rendered in the detail panel. */
enum class PartKind {
    TEXT,
    IMAGE,
    BINARY,
}

/**
 * One MIME part of a fetched message. [text] is set for [PartKind.TEXT];
 * [data] holds the decoded bytes for [PartKind.IMAGE] / [PartKind.BINARY].
 */
class MessagePart(
    val mime: String,
    val kind: PartKind,
    val text: String?,
    val data: ByteArray?,
    val filename: String?,
)
