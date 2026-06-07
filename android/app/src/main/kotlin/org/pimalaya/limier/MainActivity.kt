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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.util.concurrent.Executors
import org.pimalaya.limier.client.Account
import org.pimalaya.limier.client.ImapClient
import org.pimalaya.limier.client.MailboxHits
import org.pimalaya.limier.client.Sasl
import org.pimalaya.limier.client.SearchListener

/**
 * Single-activity host. The config panel and the merged search/results
 * panel are swapped through a [ViewFlipper]. Search runs through
 * [ImapClient] off the main thread and streams mailbox sections into
 * the results list as they arrive; each section folds independently.
 */
class MainActivity : Activity() {
    private val client = ImapClient()
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var store: SecureStore
    private lateinit var flipper: ViewFlipper

    private var matchCount = 0
    private var mailboxCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SecureStore(this)
        flipper = findViewById(R.id.flipper)

        setUpConfigPanel()
        setUpMainPanel()

        if (store.load() != null) {
            show(PANEL_MAIN)
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
            show(PANEL_MAIN)
        }
    }

    private fun setUpMainPanel() {
        val keywords = findViewById<EditText>(R.id.search_keywords)

        findViewById<Button>(R.id.search_submit).setOnClickListener { startSearch() }
        findViewById<Button>(R.id.search_edit_account).setOnClickListener { show(PANEL_CONFIG) }

        keywords.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }
    }

    private fun startSearch() {
        val query = findViewById<EditText>(R.id.search_keywords).text.toString().trim()
        if (query.isEmpty()) {
            toast(getString(R.string.search_empty))
            return
        }

        val account = store.load()
        if (account == null) {
            show(PANEL_CONFIG)
            return
        }

        val container = findViewById<LinearLayout>(R.id.results_container)
        val status = findViewById<TextView>(R.id.search_status)
        val progress = findViewById<ProgressBar>(R.id.search_progress)
        val submit = findViewById<Button>(R.id.search_submit)

        container.removeAllViews()
        matchCount = 0
        mailboxCount = 0
        status.text = getString(R.string.search_running)
        progress.visibility = View.VISIBLE
        submit.isEnabled = false

        background.execute {
            client.search(
                account,
                query,
                object : SearchListener {
                    override fun onMailbox(hits: MailboxHits) {
                        main.post {
                            addSection(hits)
                            matchCount += hits.hits.size
                            mailboxCount++
                            status.text = liveSummary()
                        }
                    }

                    override fun onFinished(error: String?) {
                        main.post {
                            progress.visibility = View.GONE
                            submit.isEnabled = true
                            status.text =
                                when {
                                    matchCount > 0 -> liveSummary()
                                    error != null -> error
                                    else -> getString(R.string.results_none)
                                }
                        }
                    }
                },
            )
        }
    }

    /** Appends a foldable section for one mailbox's hits. */
    private fun addSection(hits: MailboxHits) {
        val container = findViewById<LinearLayout>(R.id.results_container)

        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, dp(4))
            }
        for (hit in hits.hits) {
            val subject = hit.subject.ifEmpty { getString(R.string.results_no_subject) }
            body.addView(
                TextView(this).apply {
                    text = "$subject\n${hit.date}  ·  UID ${hit.uid}"
                    setTextAppearance(R.style.HitRow)
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
        }

        val header =
            TextView(this).apply {
                setTextAppearance(R.style.MailboxHeader)
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
            }
        fun render() {
            val arrow = if (body.visibility == View.VISIBLE) "▾" else "▸"
            header.text = "$arrow ${hits.mailbox}  (${hits.hits.size})"
        }
        render()
        header.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }

        container.addView(header)
        container.addView(body)
    }

    private fun liveSummary(): String =
        getString(R.string.results_live, matchCount, mailboxCount)

    private fun show(panel: Int) {
        flipper.displayedChild = panel
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PANEL_CONFIG = 0
        const val PANEL_MAIN = 1
        const val DEFAULT_PORT = 993
    }
}
