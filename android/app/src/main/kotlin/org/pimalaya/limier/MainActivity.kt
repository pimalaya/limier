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

package org.pimalaya.limier

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.util.concurrent.Executors
import org.pimalaya.limier.client.Account
import org.pimalaya.limier.client.ImapClient
import org.pimalaya.limier.client.MailboxHits
import org.pimalaya.limier.client.Sasl

/**
 * Single-activity host for the three panels (config, search, results),
 * swapped through a [ViewFlipper]. All IMAP work goes through
 * [ImapClient] on a background thread; the UI never sees sockets or JNI.
 */
class MainActivity : Activity() {
    private val client = ImapClient()
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var store: SecureStore
    private lateinit var flipper: ViewFlipper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SecureStore(this)
        flipper = findViewById(R.id.flipper)

        setUpConfigPanel()
        setUpSearchPanel()

        // Returning users skip straight to search.
        if (store.load() != null) {
            show(PANEL_SEARCH)
        }
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun setUpConfigPanel() {
        val domain = findViewById<EditText>(R.id.config_domain)
        val port = findViewById<EditText>(R.id.config_port)
        val sasl = findViewById<Spinner>(R.id.config_sasl)
        val login = findViewById<EditText>(R.id.config_login)
        val password = findViewById<EditText>(R.id.config_password)

        store.load()?.let { account ->
            domain.setText(account.domain)
            port.setText(account.port.toString())
            sasl.setSelection(account.sasl.ordinal)
            login.setText(account.login)
            password.setText(account.password)
        }

        findViewById<Button>(R.id.config_submit).setOnClickListener {
            val account =
                Account(
                    domain = domain.text.toString().trim(),
                    port = port.text.toString().trim().toIntOrNull() ?: DEFAULT_PORT,
                    sasl = Sasl.entries[sasl.selectedItemPosition],
                    login = login.text.toString().trim(),
                    password = password.text.toString(),
                )

            if (account.domain.isEmpty() || account.login.isEmpty()) {
                toast(getString(R.string.config_incomplete))
                return@setOnClickListener
            }

            store.save(account)
            show(PANEL_SEARCH)
        }
    }

    private fun setUpSearchPanel() {
        val keywords = findViewById<EditText>(R.id.search_keywords)

        findViewById<Button>(R.id.search_submit).setOnClickListener {
            val query = keywords.text.toString().trim()
            if (query.isEmpty()) {
                toast(getString(R.string.search_empty))
                return@setOnClickListener
            }

            val account = store.load()
            if (account == null) {
                show(PANEL_CONFIG)
                return@setOnClickListener
            }

            runSearch(account, query)
        }

        findViewById<Button>(R.id.results_back).setOnClickListener { show(PANEL_SEARCH) }
        findViewById<Button>(R.id.search_edit_account).setOnClickListener { show(PANEL_CONFIG) }
    }

    private fun runSearch(account: Account, query: String) {
        val status = findViewById<TextView>(R.id.search_status)
        val submit = findViewById<Button>(R.id.search_submit)

        submit.isEnabled = false
        status.text = getString(R.string.search_running)

        background.execute {
            val outcome = runCatching { client.searchAll(account, query) }

            main.post {
                submit.isEnabled = true
                status.text = ""

                outcome
                    .onSuccess { mailboxes ->
                        renderResults(query, mailboxes)
                        show(PANEL_RESULTS)
                    }
                    .onFailure { error ->
                        toast(error.message ?: getString(R.string.search_failed))
                    }
            }
        }
    }

    private fun renderResults(query: String, mailboxes: List<MailboxHits>) {
        val container = findViewById<LinearLayout>(R.id.results_container)
        container.removeAllViews()

        val total = mailboxes.sumOf { it.hits.size }
        findViewById<TextView>(R.id.results_summary).text =
            getString(R.string.results_summary, total, query)

        if (mailboxes.isEmpty()) {
            container.addView(line(getString(R.string.results_none), R.style.MailboxHeader))
            return
        }

        for (mailbox in mailboxes) {
            container.addView(
                line("${mailbox.mailbox}  (${mailbox.hits.size})", R.style.MailboxHeader)
            )
            for (hit in mailbox.hits) {
                val subject = hit.subject.ifEmpty { getString(R.string.results_no_subject) }
                container.addView(line("$subject\n${hit.date}  ·  UID ${hit.uid}", R.style.HitRow))
            }
        }
    }

    private fun line(text: String, styleRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextAppearance(styleRes)
        }

    private fun show(panel: Int) {
        flipper.displayedChild = panel
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val PANEL_CONFIG = 0
        const val PANEL_SEARCH = 1
        const val PANEL_RESULTS = 2
        const val DEFAULT_PORT = 993
    }
}
