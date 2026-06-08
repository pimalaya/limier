package org.pimalaya.limier

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.pimalaya.limier.client.Account
import org.pimalaya.limier.client.Hit
import org.pimalaya.limier.client.ImapClient
import org.pimalaya.limier.client.MailboxHits
import org.pimalaya.limier.client.MessagePart
import org.pimalaya.limier.client.PartKind
import org.pimalaya.limier.client.Sasl
import org.pimalaya.limier.client.SearchHandle
import org.pimalaya.limier.client.SearchListener

/**
 * Single-activity host. The auth, search, message and about frames live in a [ViewFlipper] under a
 * shared top bar, and are navigated as a back stack: search, message and about push onto it, the
 * bar's back arrow (and the system back button) pop, and popping the root frame quits. Search
 * streams foldable per-mailbox lists of date and subject, newest first; tapping a row fetches and
 * parses the message into foldable MIME-part sections.
 */
class MainActivity : Activity() {
    private val client = ImapClient()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tableDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    private lateinit var store: SecureStore
    private lateinit var flipper: ViewFlipper

    private val stack = ArrayDeque<Int>()

    private var handle: SearchHandle? = null
    private var searching = false
    private var searchGeneration = 0
    private var matchCount = 0
    private var mailboxCount = 0
    private var pendingDownload: MessagePart? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SecureStore(this)
        flipper = findViewById(R.id.flipper)

        setUpTopBar()
        setUpConfigPanel()
        setUpMainPanel()

        // Credentials present means the account is already usable, so the
        // search frame is the root; otherwise auth is the root and must
        // be cleared before anything else can be reached.
        resetStack(if (store.load() != null) PANEL_MAIN else PANEL_CONFIG)
    }

    override fun onDestroy() {
        handle?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (!popFrame()) {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        val part = pendingDownload ?: return
        pendingDownload = null

        try {
            contentResolver.openOutputStream(uri)?.use { it.write(part.data ?: ByteArray(0)) }
            toast(getString(R.string.download_saved))
        } catch (error: Exception) {
            toast(getString(R.string.download_failed))
        }
    }

    private fun setUpTopBar() {
        findViewById<ImageButton>(R.id.top_back).setOnClickListener { popFrame() }
        findViewById<ImageButton>(R.id.top_settings).setOnClickListener { pushFrame(PANEL_CONFIG) }
        findViewById<ImageButton>(R.id.top_about).setOnClickListener { pushFrame(PANEL_ABOUT) }
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

            verifyAndSave(account)
        }
    }

    /**
     * Proves the account connects and authenticates before persisting it. Only on success are the
     * credentials saved and the stack reset to the search frame.
     */
    private fun verifyAndSave(account: Account) {
        val submit = findViewById<Button>(R.id.config_submit)
        submit.isEnabled = false
        submit.setText(R.string.config_checking)

        io.execute {
            val outcome = runCatching { client.verify(account) }
            main.post {
                submit.isEnabled = true
                submit.setText(R.string.config_submit)
                outcome
                    .onSuccess {
                        store.save(account)
                        resetStack(PANEL_MAIN)
                    }
                    .onFailure { error ->
                        toast(error.message ?: getString(R.string.config_check_failed))
                    }
            }
        }
    }

    private fun setUpMainPanel() {
        val keywords = findViewById<EditText>(R.id.search_keywords)

        findViewById<Button>(R.id.search_submit).setOnClickListener {
            if (searching) stopSearch() else startSearch()
        }

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
            resetStack(PANEL_CONFIG)
            return
        }

        findViewById<LinearLayout>(R.id.results_container).removeAllViews()
        matchCount = 0
        mailboxCount = 0
        setRunning(true)

        // Stamps this run so callbacks from a stopped or superseded search
        // are dropped instead of clobbering the current UI.
        val generation = ++searchGeneration

        handle =
            client.search(
                account,
                query,
                object : SearchListener {
                    override fun onMailbox(hits: MailboxHits) {
                        main.post {
                            if (generation != searchGeneration) return@post
                            addSection(hits)
                            matchCount += hits.hits.size
                            mailboxCount++
                            findViewById<TextView>(R.id.search_status).text = liveSummary()
                        }
                    }

                    override fun onProgress(done: Int, total: Int) {
                        main.post {
                            if (generation != searchGeneration) return@post
                            val progress = findViewById<ProgressBar>(R.id.search_progress)
                            progress.isIndeterminate = false
                            progress.max = total
                            progress.progress = done
                        }
                    }

                    override fun onFinished(error: String?) {
                        main.post {
                            if (generation != searchGeneration) return@post
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

    /** Cancels the search and flips the UI back at once, without waiting for the workers to wind down. */
    private fun stopSearch() {
        searchGeneration++
        handle?.cancel()
        handle = null
        setRunning(false)
        findViewById<TextView>(R.id.search_status).text = getString(R.string.search_stopped)
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
            progress.progress = 0
            progress.visibility = View.VISIBLE
            findViewById<TextView>(R.id.search_status).text = getString(R.string.search_running)
        } else {
            progress.visibility = View.GONE
        }
    }

    private fun addSection(hits: MailboxHits) {
        val container = findViewById<LinearLayout>(R.id.results_container)
        val title = "${hits.mailbox}  (${hits.hits.size})"
        container.addView(foldable(title, buildList(hits.mailbox, hits.hits), expanded = true))
    }

    /** A list of clickable "date: subject" rows, newest first, divided by a thin line. */
    private fun buildList(mailbox: String, hits: List<Hit>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = getDrawable(R.drawable.divider)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            hits.forEach { hit -> addView(listRow(mailbox, hit)) }
        }

    private fun listRow(mailbox: String, hit: Hit): View {
        val subject = hit.subject.ifEmpty { getString(R.string.results_no_subject) }
        return TextView(this).apply {
            text = getString(R.string.results_row, formatTableDate(hit), subject)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundResource(selectableItemBackground())
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener { showDetail(mailbox, hit) }
        }
    }

    /** Pushes the message frame, fetches the message, then renders its parts as foldable sections. */
    private fun showDetail(mailbox: String, hit: Hit) {
        findViewById<TextView>(R.id.detail_uid).text = hit.uid.toString()
        findViewById<TextView>(R.id.detail_date).text = hit.date.ifEmpty { formatTableDate(hit) }
        findViewById<TextView>(R.id.detail_subject).text =
            hit.subject.ifEmpty { getString(R.string.results_no_subject) }

        val status = findViewById<TextView>(R.id.detail_status)
        val parts = findViewById<LinearLayout>(R.id.detail_parts)
        parts.removeAllViews()
        status.text = getString(R.string.detail_loading)
        status.visibility = View.VISIBLE
        pushFrame(PANEL_DETAIL)

        val account = store.load() ?: return
        io.execute {
            val outcome = runCatching { client.fetchMessage(account, mailbox, hit.uid) }
            main.post {
                outcome
                    .onSuccess { list ->
                        status.visibility = View.GONE
                        list.forEach { part ->
                            parts.addView(foldable(part.mime, partBody(part), expanded = false))
                        }
                    }
                    .onFailure { error ->
                        status.text = error.message ?: getString(R.string.detail_failed)
                    }
            }
        }
    }

    /** Renders one MIME part: text shown, image displayed, binary opened or saved. */
    private fun partBody(part: MessagePart): View =
        when (part.kind) {
            PartKind.TEXT ->
                if (part.mime.startsWith("text/html")) {
                    htmlView(part.text.orEmpty())
                } else {
                    TextView(this).apply {
                        text = part.text.orEmpty()
                        setTextIsSelectable(true)
                        setPadding(dp(8), dp(4), 0, dp(8))
                    }
                }

            PartKind.IMAGE -> {
                val bitmap = part.data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap == null) {
                    TextView(this).apply {
                        text = getString(R.string.detail_image_error)
                        setPadding(dp(8), dp(4), 0, dp(8))
                    }
                } else {
                    ImageView(this).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        maxHeight = dp(400)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(8), dp(4), 0, dp(8))
                    }
                }
            }

            PartKind.BINARY -> {
                val name = part.filename ?: getString(R.string.attachment_default_name)
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(4), 0, dp(8))

                    addView(
                        TextView(this@MainActivity).apply {
                            text =
                                getString(
                                    R.string.part_details,
                                    name,
                                    part.mime,
                                    formatSize(part.data?.size ?: 0),
                                )
                            setTextIsSelectable(true)
                            setPadding(0, 0, 0, dp(8))
                        }
                    )

                    // Opening with an installed viewer is preferred over
                    // saving (a PDF is read, not hoarded); the Save button
                    // stays for everything else and as a fallback.
                    if (canOpen(part.mime)) {
                        addView(
                            Button(this@MainActivity).apply {
                                text = getString(R.string.open_action, name)
                                setOnClickListener { openPart(part) }
                            }
                        )
                    }

                    addView(
                        Button(this@MainActivity).apply {
                            text = getString(R.string.download_action, name)
                            setOnClickListener { startDownload(part) }
                        }
                    )
                }
            }
        }

    /** Whether an installed app can view a part of this MIME type. */
    private fun canOpen(mime: String): Boolean {
        val probe =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://$packageName.attachments/probe"), mime)
        return probe.resolveActivity(packageManager) != null
    }

    /** Caches the part and hands it to an external viewer. */
    private fun openPart(part: MessagePart) {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(AttachmentProvider.cache(this@MainActivity, part), part.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            toast(getString(R.string.open_failed))
        }
    }

    /**
     * Renders an HTML part in a contained, sandboxed WebView (an iframe equivalent): JavaScript off
     * and network loads blocked, so remote trackers and scripts never run; links open in the
     * browser.
     */
    private fun htmlView(html: String): View =
        WebView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400))
            settings.javaScriptEnabled = false
            settings.blockNetworkLoads = true
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: Exception) {}
                        return true
                    }
                }
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

    private fun startDownload(part: MessagePart) {
        pendingDownload = part
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = part.mime
                putExtra(
                    Intent.EXTRA_TITLE,
                    part.filename ?: getString(R.string.attachment_default_name),
                )
            }
        startActivityForResult(intent, REQ_SAVE)
    }

    /** A clickable header that folds [body] open and shut. */
    private fun foldable(title: String, body: View, expanded: Boolean): View {
        val header =
            TextView(this).apply {
                setTextAppearance(R.style.MailboxHeader)
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
            }
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        fun render() {
            val arrow = if (body.visibility == View.VISIBLE) "▾" else "▸"
            header.text = "$arrow $title"
        }
        render()
        header.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }
    }

    private fun formatTableDate(hit: Hit): String =
        if (hit.timestamp > 0) tableDateFormat.format(Date(hit.timestamp * 1000)) else hit.date

    private fun formatSize(bytes: Int): String =
        when {
            bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun liveSummary(): String =
        getString(R.string.results_live, matchCount, mailboxCount)

    private fun selectableItemBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    /** Pushes [panel] onto the back stack and shows it. */
    private fun pushFrame(panel: Int) {
        stack.addLast(panel)
        renderFrame()
    }

    /** Pops the top frame; returns false when the root is reached and nothing can be popped. */
    private fun popFrame(): Boolean {
        if (stack.size <= 1) {
            return false
        }
        stack.removeLast()
        renderFrame()
        return true
    }

    /** Clears the back stack down to a single root [panel]. */
    private fun resetStack(panel: Int) {
        stack.clear()
        stack.addLast(panel)
        renderFrame()
    }

    /** Shows the top frame, and reflects the stack in the bar's back arrow and settings icon. */
    private fun renderFrame() {
        val top = stack.last()
        flipper.displayedChild = top
        findViewById<ImageButton>(R.id.top_back).visibility =
            if (stack.size > 1) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.top_settings).visibility =
            if (top == PANEL_MAIN) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.top_about).visibility =
            if (top == PANEL_CONFIG) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PANEL_CONFIG = 0
        const val PANEL_MAIN = 1
        const val PANEL_DETAIL = 2
        const val PANEL_ABOUT = 3
        const val DEFAULT_PORT = 993
        const val REQ_SAVE = 1
    }
}
