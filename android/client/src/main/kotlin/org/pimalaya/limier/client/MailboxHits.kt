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
