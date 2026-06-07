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
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.pimalaya.limier.client.Account
import org.pimalaya.limier.client.Hit
import org.pimalaya.limier.client.ImapClient
import org.pimalaya.limier.client.MailboxHits
import org.pimalaya.limier.client.Sasl
import org.pimalaya.limier.client.SearchHandle
import org.pimalaya.limier.client.SearchListener

/**
 * Single-activity host. Config, the merged search/results panel and a
 * message detail panel are swapped through a [ViewFlipper]. Search runs
 * through [ImapClient] and streams foldable per-mailbox tables in as
 * they arrive; a progress bar tracks coverage and the search button
 * doubles as a stop button.
 */
class MainActivity : Activity() {
    private val client = ImapClient()
    private val main = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("dd/MM/yy, HH:mm", Locale.getDefault())

    private lateinit var store: SecureStore
    private lateinit var flipper: ViewFlipper

    private var handle: SearchHandle? = null
    private var searching = false
    private var matchCount = 0
    private var mailboxCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SecureStore(this)
        flipper = findViewById(R.id.flipper)

        setUpConfigPanel()
        setUpMainPanel()
        findViewById<Button>(R.id.detail_back).setOnClickListener { show(PANEL_MAIN) }

        if (store.load() != null) {
            show(PANEL_MAIN)
        }
    }

    override fun onDestroy() {
        handle?.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (flipper.displayedChild == PANEL_DETAIL) {
            show(PANEL_MAIN)
        } else {
            super.onBackPressed()
        }
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

        findViewById<Button>(R.id.search_submit).setOnClickListener {
            if (searching) stopSearch() else startSearch()
        }
        findViewById<Button>(R.id.search_edit_account).setOnClickListener { show(PANEL_CONFIG) }

        keywords.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH && !searching) {
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

        findViewById<LinearLayout>(R.id.results_container).removeAllViews()
        matchCount = 0
        mailboxCount = 0
        setRunning(true)

        handle =
            client.search(
                account,
                query,
                object : SearchListener {
                    override fun onMailbox(hits: MailboxHits) {
                        main.post {
                            addSection(hits)
                            matchCount += hits.hits.size
                            mailboxCount++
                            findViewById<TextView>(R.id.search_status).text = liveSummary()
                        }
                    }

                    override fun onProgress(done: Int, total: Int) {
                        main.post {
                            val progress = findViewById<ProgressBar>(R.id.search_progress)
                            progress.isIndeterminate = false
                            progress.max = total
                            progress.progress = done
                        }
                    }

                    override fun onFinished(error: String?) {
                        main.post {
                            setRunning(false)
                            findViewById<TextView>(R.id.search_status).text =
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

    private fun stopSearch() {
        handle?.cancel()
    }

    /** Flips the search button into a stop button and locks the input. */
    private fun setRunning(running: Boolean) {
        searching = running

        val submit = findViewById<Button>(R.id.search_submit)
        val keywords = findViewById<EditText>(R.id.search_keywords)
        val progress = findViewById<ProgressBar>(R.id.search_progress)

        submit.setText(if (running) R.string.search_stop else R.string.search_submit)
        keywords.isEnabled = !running

        if (running) {
            progress.isIndeterminate = true
            progress.visibility = View.VISIBLE
            findViewById<TextView>(R.id.search_status).text = getString(R.string.search_running)
        } else {
            progress.visibility = View.GONE
        }
    }

    /** Appends a foldable section: a clickable header over a scrollable table. */
    private fun addSection(hits: MailboxHits) {
        val container = findViewById<LinearLayout>(R.id.results_container)
        val table = buildTable(hits.hits)

        val header =
            TextView(this).apply {
                setTextAppearance(R.style.MailboxHeader)
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
            }
        fun render() {
            val arrow = if (table.visibility == View.VISIBLE) "▾" else "▸"
            header.text = "$arrow ${hits.mailbox}  (${hits.hits.size})"
        }
        render()
        header.setOnClickListener {
            table.visibility = if (table.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }

        container.addView(header)
        container.addView(table)
    }

    /** A horizontally scrollable UID / Subject / Date table. */
    private fun buildTable(hits: List<Hit>): View {
        val table =
            TableLayout(this).apply {
                addView(
                    row(
                        cell(getString(R.string.column_uid), bold = true),
                        cell(getString(R.string.column_subject), bold = true),
                        cell(getString(R.string.column_date), bold = true),
                    )
                )
            }

        for (hit in hits) {
            val subject = hit.subject.ifEmpty { getString(R.string.results_no_subject) }
            val tableRow =
                row(
                    cell(hit.uid.toString()),
                    cell(subject),
                    cell(formatDate(hit)),
                )
            tableRow.isClickable = true
            tableRow.setBackgroundResource(selectableItemBackground())
            tableRow.setOnClickListener { showDetail(hit) }
            table.addView(tableRow)
        }

        return HorizontalScrollView(this).apply { addView(table) }
    }

    private fun row(vararg cells: TextView): TableRow =
        TableRow(this).apply { cells.forEach { addView(it) } }

    private fun cell(text: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            isSingleLine = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun showDetail(hit: Hit) {
        findViewById<TextView>(R.id.detail_uid).text = hit.uid.toString()
        findViewById<TextView>(R.id.detail_subject).text =
            hit.subject.ifEmpty { getString(R.string.results_no_subject) }
        findViewById<TextView>(R.id.detail_date).text = formatDate(hit)
        show(PANEL_DETAIL)
    }

    private fun formatDate(hit: Hit): String =
        if (hit.timestamp > 0) dateFormat.format(Date(hit.timestamp * 1000)) else hit.date

    private fun liveSummary(): String =
        getString(R.string.results_live, matchCount, mailboxCount)

    private fun selectableItemBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

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
        const val PANEL_DETAIL = 2
        const val DEFAULT_PORT = 993
    }
}
