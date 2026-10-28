package com.freecaster.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var toolbar: MaterialToolbar
    private var tunnelIcon: ImageView? = null
    private var tunnelTraffic: TextView? = null
    private var lastTunnelState = SshTunnel.State.OFF
    private lateinit var address: EditText
    private lateinit var addressIcon: ImageView
    private lateinit var addressClear: ImageButton
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private var castContext: CastContext? = null

    private val videos = LinkedHashMap<String, DetectedVideo>()
    private val probed = HashSet<String>()
    private var pageGen = 0 // so probe results for an old page don't land on the new one
    @Volatile private var adBlock = true
    @Volatile private var userAgent = ""

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }

    private val tunnelListener: (SshTunnel.Status) -> Unit = { st ->
        // Show an error once, when it appears
        if (st.state == SshTunnel.State.ERROR && lastTunnelState != SshTunnel.State.ERROR) {
            toast(getString(R.string.ssh_error_toast, SshTunnel.describe(this, st)))
        }
        lastTunnelState = st.state
        updateTunnelIcon()
    }
    private val tunnelRefresh = Runnable { updateTunnelIcon() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        web = findViewById(R.id.web)
        address = findViewById(R.id.address)
        addressIcon = findViewById(R.id.address_icon)
        addressClear = findViewById(R.id.address_clear)
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.progress)

        castContext = try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            toast(getString(R.string.cast_unavailable))
            null
        }

        adBlock = prefs.getBoolean("adblock", true)
        setupWebView()
        setupAddressBar()
        fab.setOnClickListener { showVideoList() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // The tunnel was on: set up the WebView proxy first, then load the first page (kill switch)
        val firstLoad = {
            if (!handleIntent(intent)) {
                if (savedInstanceState != null) web.restoreState(savedInstanceState)
                else web.loadUrl(prefs.getString("last", HOME) ?: HOME)
            }
        }
        if (SshSettings.wasEnabled(this) && !SshTunnel.isEnabled) {
            if (!SshTunnel.start(this) { firstLoad() }) firstLoad()
        } else firstLoad()
    }

    override fun onStart() {
        super.onStart()
        SshTunnel.addListener(tunnelListener)
        updateTunnelIcon()
    }

    override fun onStop() {
        SshTunnel.removeListener(tunnelListener)
        toolbar.removeCallbacks(tunnelRefresh)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        AudioRelay.handleVolumeKey(this, event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.url?.let { prefs.edit().putString("last", it).apply() }
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** A link shared from another app or opened with this app. */
    private fun handleIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        } ?: return false
        web.loadUrl(url)
        return true
    }

    // ---------------- Browser ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true) // together with onCreateWindow=false this blocks pop-ups
            javaScriptCanOpenWindowsAutomatically = false
            userAgent = userAgentString
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "CasterBridge")
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(web, VideoDetector.SCAN_JS, setOf("*"))

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (adBlock && !req.isForMainFrame && AdBlocker.isAd(req.url.host)) {
                    return AdBlocker.emptyResponse()
                }
                val url = req.url.toString()
                VideoDetector.classify(url)?.let { kind ->
                    val video = DetectedVideo(url, kind, buildHeaders(url, req.requestHeaders))
                    runOnUiThread { addVideo(video) }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val scheme = req.url.scheme ?: return true
                if (scheme != "http" && scheme != "https") return true // intent://, market:// are usually ads
                if (adBlock && AdBlocker.isAd(req.url.host)) return true
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                clearVideos()
                showUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!docStart) view.evaluateJavascript(VideoDetector.SCAN_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                showUrl(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progress.setProgressCompat(newProgress, true)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
                false // block pop-up windows

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun buildHeaders(url: String, req: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in req) {
            when (k.lowercase()) {
                "referer" -> out["Referer"] = v
                "origin" -> out["Origin"] = v
                "user-agent" -> out["User-Agent"] = v
            }
        }
        if ("User-Agent" !in out) out["User-Agent"] = userAgent
        CookieManager.getInstance().getCookie(url)?.let { out["Cookie"] = it }
        return out
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onVideo(src: String, page: String, duration: Double) {
            if (!src.startsWith("http")) return
            val kind = VideoDetector.classify(src) ?: VideoKind.OTHER
            val headers = buildHeaders(src, mapOf("Referer" to page))
            val d = when {
                duration > 0 -> duration
                duration == -1.0 -> VideoProbe.LIVE
                else -> null
            }
            runOnUiThread { addVideo(DetectedVideo(src, kind, headers, d)) }
        }
    }

    private fun setupAddressBar() {
        address.setOnFocusChangeListener { _, focused ->
            addressClear.visibility = if (focused) View.VISIBLE else View.GONE
            if (focused) addressIcon.setImageResource(R.drawable.ic_search) else web.url?.let { showUrl(it) }
        }
        addressClear.setOnClickListener { address.text.clear() }
        address.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                web.loadUrl(toUrl(address.text.toString()))
                address.clearFocus()
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
                web.requestFocus()
                true
            } else false
        }
    }

    /** Lock icon for HTTPS, search icon otherwise. */
    private fun showUrl(url: String) {
        if (address.hasFocus()) return
        address.setText(url)
        addressIcon.setImageResource(if (url.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_search)
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }

    // ---------------- Found videos ----------------

    private fun addVideo(v: DetectedVideo) {
        val existing = videos[v.url]
        if (existing == null && videos.size >= 60) return
        val merged = if (existing == null) v else existing.copy(
            kind = if (existing.kind == VideoKind.OTHER) v.kind else existing.kind,
            duration = v.duration ?: existing.duration,
        )
        if (merged == existing) return
        videos[v.url] = merged
        updateFab()
        if (merged.duration == null && probed.add(v.url)) {
            val gen = pageGen
            VideoProbe.probe(merged) { d ->
                if (d != null) runOnUiThread { if (gen == pageGen) setDuration(v.url, d) }
            }
        }
    }

    private fun setDuration(url: String, d: Double) {
        val v = videos[url] ?: return
        if (v.duration != null) return
        videos[url] = v.copy(duration = d)
        updateFab()
    }

    private fun clearVideos() {
        pageGen++
        videos.clear()
        probed.clear()
        updateFab()
    }

    private fun updateFab() {
        val main = videos.values.count { !it.isShort }
        fab.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        fab.text = if (main > 0) getString(R.string.videos_count, main) else getString(R.string.short_count, videos.size)
    }

    private fun formatDuration(d: Double?): String? {
        if (d == null) return null
        if (d == VideoProbe.LIVE) return getString(R.string.live)
        val t = d.toLong()
        val h = t / 3600
        val m = t % 3600 / 60
        val s = t % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun kindLabel(k: VideoKind) = if (k == VideoKind.OTHER) getString(R.string.kind_video) else k.label

    private fun showVideoList(showShort: Boolean = false) {
        // Live streams and the longest videos first (usually what you want),
        // then unknown durations by type: HLS, MP4…
        val all = videos.values.sortedWith(
            compareByDescending<DetectedVideo> { v ->
                val d = v.duration ?: -2.0
                if (d == VideoProbe.LIVE) Double.MAX_VALUE else d
            }.thenBy { it.kind.ordinal }
        )
        val shortCount = all.count { it.isShort }
        val list = if (showShort) all else all.filter { !it.isShort }
        if (list.isEmpty() && !showShort) return showVideoList(showShort = true)

        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_videos, null)
        view.findViewById<TextView>(R.id.sheet_title).text = getString(R.string.found_videos)
        val items = view.findViewById<LinearLayout>(R.id.video_items)
        for (v in list) {
            val item = layoutInflater.inflate(R.layout.item_video, items, false)
            val u = Uri.parse(v.url)
            item.findViewById<TextView>(R.id.video_kind).text = kindLabel(v.kind)
            item.findViewById<TextView>(R.id.video_title).text = u.lastPathSegment ?: v.url
            item.findViewById<TextView>(R.id.video_host).text = u.host ?: ""
            item.findViewById<TextView>(R.id.video_duration).apply {
                text = formatDuration(v.duration) ?: ""
                if (v.duration == VideoProbe.LIVE) setTextColor(ContextCompat.getColor(context, R.color.tunnel_error))
            }
            item.setOnClickListener {
                sheet.dismiss()
                showVideoActions(v)
            }
            items.addView(item)
        }
        // Short clips (under a minute) are usually ads, so they're hidden
        view.findViewById<Button>(R.id.show_short).apply {
            if (!showShort && shortCount > 0) {
                visibility = View.VISIBLE
                text = getString(R.string.show_short, shortCount)
                setOnClickListener {
                    sheet.dismiss()
                    showVideoList(showShort = true)
                }
            }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun showVideoActions(v: DetectedVideo) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, null)
        val u = Uri.parse(v.url)
        view.findViewById<TextView>(R.id.action_title).text = u.lastPathSegment ?: v.url
        view.findViewById<TextView>(R.id.action_subtitle).text =
            listOfNotNull(kindLabel(v.kind), formatDuration(v.duration), u.host).joinToString(" · ")
        val items = view.findViewById<LinearLayout>(R.id.action_items)
        fun action(icon: Int, title: Int, run: () -> Unit) {
            val row = layoutInflater.inflate(R.layout.item_action, items, false) as TextView
            row.setText(title)
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            row.setOnClickListener {
                sheet.dismiss()
                run()
            }
            items.addView(row)
        }
        action(R.drawable.ic_cast, R.string.action_cast) { cast(v, viaPhone = false) }
        action(R.drawable.ic_phone, R.string.action_cast_phone) { cast(v, viaPhone = true) }
        action(R.drawable.ic_open, R.string.action_open) { openExternal(v) }
        action(R.drawable.ic_copy, R.string.action_copy) { copy(v.url) }
        sheet.setContentView(view)
        sheet.show()
    }

    // ---------------- Chromecast ----------------

    private fun cast(v: DetectedVideo, viaPhone: Boolean) {
        // The Chromecast can't use the tunnel itself, so with the tunnel on video only goes via the phone
        if (!viaPhone && SshTunnel.isEnabled) return cast(v, viaPhone = true)
        val session = castContext?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        if (session == null || !session.isConnected || client == null) {
            toast(getString(R.string.pick_chromecast))
            return
        }
        val url = if (viaPhone) {
            val ip = NetUtils.localIpv4(this) ?: run {
                toast(getString(R.string.same_wifi))
                return
            }
            ProxyService.start(this)
            StreamProxy.proxify(ip, v.url, v.headers)
        } else v.url

        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, web.title ?: getString(R.string.kind_video))
            putString(MediaMetadata.KEY_SUBTITLE, Uri.parse(v.url).host ?: "")
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(v.kind.mime)
            .setMetadata(meta)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .build()

        client.load(request).setResultCallback { result ->
            if (result.status.isSuccess) {
                AudioRelay.onCastStarted(this, v, viaPhone)
                startActivity(Intent(this, ExpandedControlsActivity::class.java))
            } else if (!viaPhone) {
                toast(getString(R.string.cast_retry_phone))
                cast(v, viaPhone = true)
            } else {
                toast(getString(R.string.cast_failed, result.status.statusCode))
            }
        }
        if (!viaPhone) ProxyService.stop(this)
    }

    private fun openExternal(v: DetectedVideo) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.url), v.kind.mime))
        } catch (e: Exception) {
            toast(getString(R.string.no_player))
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("video", text))
        toast(getString(R.string.copied))
    }

    // ---------------- Menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        menu.findItem(R.id.action_tunnel)?.actionView?.let { v ->
            tunnelIcon = v.findViewById(R.id.tunnel_icon)
            tunnelTraffic = v.findViewById(R.id.tunnel_traffic)
            v.setOnClickListener { onTunnelClick() }
            // Long press: status, settings, "Retry"
            v.setOnLongClickListener {
                if (SshSettings.load(this) == null) showSshSettings(connectAfter = true) else showTunnelStatus()
                true
            }
        }
        updateTunnelIcon()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_adblock)?.isChecked = adBlock
        menu.findItem(R.id.action_audio_phone)?.isChecked = AudioRelay.isActive
        menu.findItem(R.id.action_audio_delay)?.isVisible = AudioRelay.isActive
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_back -> if (web.canGoBack()) web.goBack()
            R.id.action_forward -> if (web.canGoForward()) web.goForward()
            R.id.action_reload -> web.reload()
            R.id.action_home -> web.loadUrl(HOME)
            R.id.action_remote -> startActivity(Intent(this, ExpandedControlsActivity::class.java))
            R.id.action_clear -> clearVideos()
            R.id.action_ssh_settings -> showSshSettings(connectAfter = false)
            R.id.action_update -> checkUpdates()
            R.id.action_audio_phone -> AudioRelay.toggle(this)
            R.id.action_audio_delay -> AudioRelay.showDelayDialog(this)
            R.id.action_language -> showLanguages()
            R.id.action_about -> showAbout()
            R.id.action_adblock -> {
                adBlock = !adBlock
                item.isChecked = adBlock
                prefs.edit().putBoolean("adblock", adBlock).apply()
                web.reload()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------- Updates ----------------

    private fun checkUpdates() {
        toast(getString(R.string.checking_updates))
        thread {
            val rel = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val r = rel.getOrElse {
                    toast(getString(R.string.update_check_failed, it.message))
                    return@runOnUiThread
                }
                val current = Updater.currentBuild(this)
                if (r.build <= current) {
                    toast(getString(R.string.up_to_date, current.toInt()))
                    return@runOnUiThread
                }
                val size = if (r.size > 0) " · ${formatBytes(r.size)}" else ""
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.update_available, r.build.toInt()))
                    .setMessage(getString(R.string.update_message, current.toInt(), size, r.notes.ifEmpty { getString(R.string.no_notes) }))
                    .setPositiveButton(R.string.update) { _, _ -> startUpdate(r) }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }
    }

    private fun startUpdate(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Granted once; after that updates are one tap
            toast(getString(R.string.allow_installs))
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            setPadding(64, 32, 64, 0)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.downloading_build, r.build.toInt()))
            .setView(bar)
            .setCancelable(false)
            .show()
        thread {
            try {
                Updater.install(this, r) { p -> runOnUiThread { bar.setProgressCompat(p, true) } }
                runOnUiThread { dlg.dismiss() }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    toast(getString(R.string.update_error, e.message))
                }
            }
        }
    }

    // ---------------- Language & about ----------------

    private fun showLanguages() {
        val tags = arrayOf("en", "uk")
        val names = arrayOf("English", "Українська")
        val current = AppCompatDelegate.getApplicationLocales()[0]?.language ?: "en"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_language)
            .setSingleChoiceItems(names, tags.indexOf(current).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                // The activity is recreated in the new language
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[i]))
            }
            .show()
    }

    private fun showAbout() {
        val email = getString(R.string.support_email)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setIcon(R.mipmap.ic_launcher)
            .setMessage(getString(R.string.about_text, Updater.currentBuild(this).toInt(), email))
            .setPositiveButton(R.string.write_email) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                        .putExtra(Intent.EXTRA_SUBJECT, "FreeCaster"))
                } catch (e: Exception) {
                    copy(email)
                }
            }
            .setNeutralButton(R.string.copy) { _, _ -> copy(email) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------------- SSH tunnel ----------------

    private fun onTunnelClick() {
        when {
            SshSettings.load(this) == null -> showSshSettings(connectAfter = true)
            !SshTunnel.isEnabled -> connectTunnel()
            // Needs a decision: whether to trust the new server key
            SshTunnel.status.hostKeyChanged -> showTunnelStatus()
            // Plain toggle: turn off right away, no dialog
            else -> SshTunnel.stop(this) { web.reload() }
        }
    }

    private fun connectTunnel() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            toast(getString(R.string.webview_no_proxy))
            return
        }
        if (!SshTunnel.start(this) { web.reload() }) toast(getString(R.string.tunnel_start_failed))
    }

    private fun showTunnelStatus() {
        val st = SshTunnel.status
        val cfg = SshSettings.load(this)
        val msg = SshTunnel.describe(this, st) + (cfg?.let { "\n\n${it.user}@${it.host}:${it.port}" } ?: "")
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssh_tunnel)
            .setMessage(msg)
            .setNeutralButton(R.string.settings) { _, _ -> showSshSettings(connectAfter = false) }
        if (SshTunnel.isEnabled) {
            b.setNegativeButton(R.string.disconnect) { _, _ -> SshTunnel.stop(this) { web.reload() } }
        }
        if (st.state == SshTunnel.State.ERROR) {
            b.setPositiveButton(if (st.hostKeyChanged) R.string.trust_new_key else R.string.retry) { _, _ ->
                if (st.hostKeyChanged) SshSettings.forgetHostKey(this)
                SshTunnel.retry(this)
            }
        } else {
            b.setPositiveButton(R.string.close, null)
        }
        b.show()
    }

    private fun showSshSettings(connectAfter: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_ssh, null)
        val host = view.findViewById<TextInputEditText>(R.id.ssh_host)
        val port = view.findViewById<TextInputEditText>(R.id.ssh_port)
        val user = view.findViewById<TextInputEditText>(R.id.ssh_user)
        val pass = view.findViewById<TextInputEditText>(R.id.ssh_pass)
        SshSettings.load(this)?.let {
            host.setText(it.host)
            port.setText(it.port.toString())
            user.setText(it.user)
            pass.setText(it.password)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssh_server)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val cfg = SshSettings.parse(
                    host.text.toString(), port.text.toString(), user.text.toString(), pass.text.toString()
                )
                if (cfg == null) {
                    toast(getString(R.string.ssh_fill_all))
                    return@setPositiveButton
                }
                SshSettings.save(this, cfg)
                when {
                    SshTunnel.isEnabled -> SshTunnel.restart(this)
                    connectAfter -> connectTunnel()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Grey: off; green: working (and the first 10 s of a silent reconnect);
     * yellow: connecting; red: error or the server unreachable for over a minute.
     */
    private fun updateTunnelIcon() {
        val icon = tunnelIcon ?: return
        val st = SshTunnel.status
        val elapsed = SystemClock.elapsedRealtime() - st.since
        val colorRes = when (st.state) {
            SshTunnel.State.OFF -> null
            SshTunnel.State.CONNECTED -> R.color.tunnel_ok
            SshTunnel.State.RECONNECTING -> when {
                elapsed < 10_000 -> R.color.tunnel_ok
                elapsed < 60_000 -> R.color.tunnel_wait
                else -> R.color.tunnel_error
            }
            SshTunnel.State.CONNECTING -> if (elapsed < 60_000) R.color.tunnel_wait else R.color.tunnel_error
            SshTunnel.State.ERROR -> R.color.tunnel_error
        }
        val color = colorRes?.let { ContextCompat.getColor(this, it) }
            ?: MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant)
        icon.imageTintList = ColorStateList.valueOf(color)
        tunnelTraffic?.apply {
            visibility = if (SshTunnel.isEnabled) View.VISIBLE else View.GONE
            text = formatBytes(SshTunnel.trafficBytes)
        }

        // While the tunnel is on, refresh the counter and color every second
        toolbar.removeCallbacks(tunnelRefresh)
        if (SshTunnel.isEnabled) toolbar.postDelayed(tunnelRefresh, 1_000)
    }

    private fun formatBytes(b: Long): String {
        val mb = b / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", mb / 1024)
            mb >= 100 -> "${mb.toLong()} MB"
            else -> String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    // ---------------- Browser fullscreen ----------------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        (window.decorView as ViewGroup).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(v)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val HOME = "https://www.google.com"
    }
}
