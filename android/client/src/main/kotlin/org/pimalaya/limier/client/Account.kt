package org.pimalaya.limier.client

/** IMAP account credentials entered on the config panel. */
data class Account(
    val domain: String,
    val port: Int,
    val sasl: Sasl,
    val login: String,
    val password: String,
)

/** SASL mechanism offered by the config form. */
enum class Sasl {
    PLAIN,
    LOGIN,
}
