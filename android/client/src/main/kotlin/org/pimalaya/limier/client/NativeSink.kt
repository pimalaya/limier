package org.pimalaya.limier.client

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * The control surface the Rust bridge drives, one mailbox at a time.
 * All three methods are called by name from native code, so they stay
 * public (no Kotlin mangling) for JNI lookup:
 *
 * - [onMailbox] receives a mailbox that has hits (a malformed payload
 *   is dropped rather than thrown back across the boundary),
 * - [onProgress] fires once per mailbox processed (with or without hits),
 * - [shouldStop] is polled before each mailbox to honour cancellation.
 */
internal class NativeSink(
    private val cancelled: AtomicBoolean,
    private val onHits: (MailboxHits) -> Unit,
    private val onAdvance: () -> Unit,
) {
    fun onMailbox(json: String) {
        val hits =
            try {
                parse(json)
            } catch (_: Exception) {
                return
            }
        onHits(hits)
    }

    fun onProgress() = onAdvance()

    fun shouldStop(): Boolean = cancelled.get()

    private fun parse(json: String): MailboxHits {
        val obj = JSONObject(json)
        val hits = obj.getJSONArray("hits")

        return MailboxHits(
            mailbox = obj.getString("mailbox"),
            hits =
                (0 until hits.length()).map { index ->
                    val hit = hits.getJSONObject(index)
                    Hit(
                        uid = hit.getLong("uid"),
                        subject = hit.getString("subject"),
                        date = hit.getString("date"),
                        timestamp = hit.getLong("timestamp"),
                    )
                },
        )
    }
}
