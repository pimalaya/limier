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
