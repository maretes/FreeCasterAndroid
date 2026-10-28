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
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
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

    private var castButton: MediaRouteButton? = null

    /** Video to cast as soon as the user picks a Chromecast in the device chooser. */
    private var pendingCast: Pair<DetectedVideo, Boolean>? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        private fun ready() {
            val (v, viaPhone) = pendingCast ?: return
            pendingCast = null
            cast(v, viaPhone)
        }
        override fun onSessionStarted(session: CastSession, sessionId: String) = ready()
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = ready()
        override fun onSessionStartFailed(session: CastSession, error: Int) { pendingCast = null }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    /** The DLNA TV we last sent a video to, for the remote. */
    private var dlnaDevice: DlnaDevice? = null
    private var dlnaTitle = ""

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
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        SshTunnel.addListener(tunnelListener)
        updateTunnelIcon()
    }

    override fun onStop() {
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
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
        fun onVideo(src: String, page: String, duration: Double, height: Int) {
            if (!src.startsWith("http")) return
            val kind = VideoDetector.classify(src) ?: VideoKind.OTHER
            val headers = buildHeaders(src, mapOf("Referer" to page))
            val d = when {
                duration > 0 -> duration
                duration == -1.0 -> VideoProbe.LIVE
                else -> null
            }
            runOnUiThread { addVideo(DetectedVideo(src, kind, headers, d, height.takeIf { it > 0 })) }
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
            height = existing.height ?: v.height,
        )
        if (merged == existing) return
        videos[v.url] = merged
        updateFab()
        // The page often knows the duration but not the quality (or the other way round)
        if ((merged.duration == null || merged.height == null) && probed.add(v.url)) {
            val gen = pageGen
            VideoProbe.probe(merged) { r ->
                runOnUiThread { if (gen == pageGen) applyProbe(v.url, r) }
            }
        }
    }

    private fun applyProbe(url: String, r: VideoProbe.Result) {
        val v = videos[url] ?: return
        val updated = v.copy(
            duration = v.duration ?: r.duration,
            // A master playlist's best quality beats what the page happens to be playing right now
            height = if (r.adaptive) r.height ?: v.height else v.height ?: r.height,
            adaptive = v.adaptive || r.adaptive,
        )
        if (updated == v) return
        videos[url] = updated
        updateFab()
    }

    /** "1080p", "4K", "Auto ≤1080p"; from the probe, a probed master playlist, or a hint in the URL. */
    private fun qualityLabel(v: DetectedVideo): String? {
        val h = v.height ?: VideoProbe.knownHeight(v.url) ?: urlHeight.find(v.url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val label = when {
            h >= 2_000 -> "4K"
            h >= 1_400 -> "1440p"
            else -> "${h}p"
        }
        return if (v.adaptive) getString(R.string.quality_auto, label) else label
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
            }.thenByDescending { it.height ?: 0 }.thenBy { it.kind.ordinal }
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
            item.findViewById<TextView>(R.id.video_host).text =
                listOfNotNull(qualityLabel(v), u.host).joinToString(" · ")
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
            listOfNotNull(kindLabel(v.kind), qualityLabel(v), formatDuration(v.duration), u.host).joinToString(" · ")
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
        action(R.drawable.ic_tv, R.string.action_dlna) { showDlnaPicker(v) }
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
            // Not connected yet: open the device chooser and cast once a Chromecast is picked
            val button = castButton
            if (button != null && castContext != null) {
                pendingCast = v to viaPhone
                button.performClick()
            } else {
                toast(getString(R.string.pick_chromecast))
            }
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
                if (!viaPhone) watchDirectCast(client, v)
                startActivity(Intent(this, ExpandedControlsActivity::class.java))
            } else if (!viaPhone) {
                toast(getString(R.string.cast_retry_phone))
                cast(v, viaPhone = true)
            } else {
                toast(getString(R.string.cast_failed, result.status.statusCode))
            }
        }
        // The proxy may still serve a DLNA TV or phone audio
        if (!viaPhone && dlnaDevice == null && !AudioRelay.isActive) ProxyService.stop(this)
    }

    /**
     * The Chromecast accepted the link but may fail a moment later, when the site refuses it
     * without the browser's Referer/Cookie. Watch for that for 20 s and retry via the phone.
     */
    private fun watchDirectCast(client: RemoteMediaClient, v: DetectedVideo) {
        var done = false
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val st = client.mediaStatus ?: return
                if (done || st.playerState != MediaStatus.PLAYER_STATE_IDLE ||
                    st.idleReason != MediaStatus.IDLE_REASON_ERROR
                ) return
                done = true
                client.unregisterCallback(this)
                toast(getString(R.string.cast_retry_phone))
                cast(v, viaPhone = true)
            }
        }
        client.registerCallback(callback)
        toolbar.postDelayed({
            if (!done) {
                done = true
                client.unregisterCallback(callback)
            }
        }, 20_000)
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
        castButton = menu.findItem(R.id.media_route_menu_item)?.actionView as? MediaRouteButton
        menu.findItem(R.id.action_tunnel)?.actionView?.let { v ->
            tunnelIcon = v.findViewById(R.id.tunnel_icon)
            tunnelTraffic = v.findViewById(R.id.tunnel_traffic)
            v.setOnClickListener { onTunnelClick() }
            // Long press: status, settings, "Retry"
            v.setOnLongClickListener {
                if (!ConnectionSettings.isConfigured(this)) showConnectionChooser(connectAfter = true) else showTunnelStatus()
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
        menu.findItem(R.id.action_dlna_remote)?.isVisible = dlnaDevice != null
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
            R.id.action_ssh_settings -> showConnectionChooser(connectAfter = false)
            R.id.action_update -> checkUpdates()
            R.id.action_audio_phone -> AudioRelay.toggle(this)
            R.id.action_audio_delay -> AudioRelay.showDelayDialog(this)
            R.id.action_language -> showLanguages()
            R.id.action_dlna_remote -> showDlnaRemote()
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

    // ---------------- DLNA TVs ----------------

    private fun showDlnaPicker(v: DetectedVideo) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_videos, null)
        view.findViewById<TextView>(R.id.sheet_title).setText(R.string.dlna_title)
        val items = view.findViewById<LinearLayout>(R.id.video_items)
        val refresh = view.findViewById<Button>(R.id.show_short)
        val hint = TextView(this).apply {
            setPadding(30, 8, 30, 24)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        items.addView(hint)
        var searchGen = 0

        fun search() {
            val gen = ++searchGen
            items.removeAllViews()
            items.addView(hint)
            hint.visibility = View.VISIBLE
            hint.setText(R.string.dlna_searching)
            refresh.visibility = View.GONE
            var found = 0
            thread {
                val report = runCatching {
                    Dlna.discover(this) { d ->
                        runOnUiThread {
                            if (gen != searchGen || !sheet.isShowing) return@runOnUiThread
                            found++
                            hint.visibility = View.GONE
                            val item = layoutInflater.inflate(R.layout.item_video, items, false)
                            item.findViewById<TextView>(R.id.video_kind).text = "DLNA"
                            item.findViewById<TextView>(R.id.video_title).text = d.name
                            item.findViewById<TextView>(R.id.video_host).text =
                                listOf(d.model, d.host).filter { it.isNotEmpty() }.joinToString(" · ")
                            item.setOnClickListener {
                                sheet.dismiss()
                                castDlna(v, d)
                            }
                            items.addView(item)
                        }
                    }
                }.getOrDefault(emptyMap())
                runOnUiThread {
                    if (gen != searchGen || !sheet.isShowing) return@runOnUiThread
                    // Nobody answering at all points at the network, not at the TV
                    if (found == 0) {
                        hint.text = if (report.isEmpty()) getString(R.string.dlna_blocked)
                        else getString(R.string.dlna_none_n, report.size) + "\n\n" +
                            report.entries.sortedBy { it.key }.joinToString("\n") { "• ${it.key} — ${it.value}" }
                    }
                    refresh.visibility = View.VISIBLE
                }
            }
        }
        refresh.setText(R.string.refresh)
        refresh.setOnClickListener { search() }
        sheet.setContentView(view)
        sheet.show()
        search()
    }

    /** DLNA TVs always get the video through the phone: headers, tunnel and a proper Content-Type. */
    private fun castDlna(v: DetectedVideo, d: DlnaDevice) {
        val ip = NetUtils.localIpv4(this) ?: run {
            toast(getString(R.string.same_wifi))
            return
        }
        val mime = when (v.kind) {
            VideoKind.OTHER -> "video/mp4"
            VideoKind.HLS -> "application/vnd.apple.mpegurl"
            else -> v.kind.mime
        }
        val title = web.title ?: getString(R.string.kind_video)
        toast(getString(R.string.dlna_sending, d.name))
        if (v.kind == VideoKind.HLS) toast(getString(R.string.dlna_hls_note))
        ProxyService.start(this)
        val url = StreamProxy.proxify(ip, v.url, v.headers, mime)
        thread {
            val result = runCatching { Dlna.play(d, url, title, mime) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    dlnaDevice = d
                    dlnaTitle = title
                    invalidateOptionsMenu()
                    showDlnaRemote()
                }.onFailure {
                    toast(getString(R.string.dlna_failed, it.message))
                }
            }
        }
    }

    private fun showDlnaRemote() {
        val d = dlnaDevice ?: return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_dlna_remote, null)
        view.findViewById<TextView>(R.id.dlna_name).text = d.name
        view.findViewById<TextView>(R.id.dlna_title).text = dlnaTitle
        val seek = view.findViewById<Slider>(R.id.dlna_seek)
        val time = view.findViewById<TextView>(R.id.dlna_time)
        val play = view.findViewById<MaterialButton>(R.id.dlna_play)
        val volumeText = view.findViewById<TextView>(R.id.dlna_volume)
        view.findViewById<MaterialButton>(R.id.dlna_stop).setText(R.string.stop_playback)

        var position = 0L
        var duration = 0L
        var playing = true
        var volume: Int? = null
        var dragging = false

        // Every call goes over the network, so off the main thread; errors are just skipped
        fun send(action: () -> Unit) = thread { runCatching(action) }

        fun render() {
            time.text = listOfNotNull(formatDuration(position.toDouble()), if (duration > 0) formatDuration(duration.toDouble()) else null)
                .joinToString(" / ")
            if (duration > 0 && !dragging) {
                seek.valueTo = duration.toFloat()
                seek.value = position.coerceIn(0, duration).toFloat()
            }
            play.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            volumeText.text = volume?.let { getString(R.string.volume_value, it) } ?: ""
        }

        val poll = object : Runnable {
            override fun run() {
                if (!sheet.isShowing) return
                thread {
                    val pos = runCatching { Dlna.position(d) }.getOrNull()
                    val st = runCatching { Dlna.state(d) }.getOrNull()
                    val vol = runCatching { Dlna.volume(d) }.getOrNull()
                    runOnUiThread {
                        pos?.let { (p, dur) -> position = p; if (dur > 0) duration = dur }
                        st?.let { playing = it == "PLAYING" || it == "TRANSITIONING" }
                        vol?.let { volume = it }
                        render()
                    }
                }
                time.postDelayed(this, 1_000)
            }
        }

        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                position = slider.value.toLong()
                send { Dlna.seek(d, position) }
            }
        })
        play.setOnClickListener {
            playing = !playing
            render()
            send { if (playing) Dlna.resume(d) else Dlna.pause(d) }
        }
        view.findViewById<Button>(R.id.dlna_back).setOnClickListener {
            position = (position - 30).coerceAtLeast(0)
            render()
            send { Dlna.seek(d, position) }
        }
        view.findViewById<Button>(R.id.dlna_fwd).setOnClickListener {
            position += 30
            render()
            send { Dlna.seek(d, position) }
        }
        view.findViewById<Button>(R.id.dlna_vol_down).setOnClickListener {
            val v = ((volume ?: 10) - 2).coerceAtLeast(0)
            volume = v
            render()
            send { Dlna.setVolume(d, v) }
        }
        view.findViewById<Button>(R.id.dlna_vol_up).setOnClickListener {
            val v = ((volume ?: 10) + 2).coerceAtMost(100)
            volume = v
            render()
            send { Dlna.setVolume(d, v) }
        }
        view.findViewById<Button>(R.id.dlna_stop).setOnClickListener {
            send { Dlna.stop(d) }
            dlnaDevice = null
            invalidateOptionsMenu()
            sheet.dismiss()
        }
        sheet.setOnDismissListener { time.removeCallbacks(poll) }
        sheet.setContentView(view)
        sheet.show()
        render()
        poll.run()
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
            !ConnectionSettings.isConfigured(this) -> showConnectionChooser(connectAfter = true)
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
        val details = if (ConnectionSettings.mode(this) == ConnectionSettings.Mode.PROXY) {
            ConnectionSettings.proxy(this)?.let { "${it.protocol.uppercase()} ${it.label}" }
        } else {
            SshSettings.load(this)?.let { "SSH ${it.user}@${it.host}:${it.port}" }
        }
        val msg = SshTunnel.describe(this, st) + (details?.let { "\n\n$it" } ?: "")
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssh_tunnel)
            .setMessage(msg)
            .setNeutralButton(R.string.settings) { _, _ -> showConnectionChooser(connectAfter = false) }
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
                ConnectionSettings.setMode(this, ConnectionSettings.Mode.SSH)
                when {
                    SshTunnel.isEnabled -> SshTunnel.restart(this)
                    connectAfter -> connectTunnel()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** SSH server or a public proxy. */
    private fun showConnectionChooser(connectAfter: Boolean) {
        val current = if (ConnectionSettings.mode(this) == ConnectionSettings.Mode.PROXY) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_connection)
            .setSingleChoiceItems(arrayOf(getString(R.string.conn_ssh), getString(R.string.conn_proxy)), current) { d, i ->
                d.dismiss()
                if (i == 0) showSshSettings(connectAfter) else showProxyList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProxyList() {
        if (!ConnectionSettings.warned(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_warning_title)
                .setMessage(R.string.proxy_warning)
                .setPositiveButton(R.string.got_it) { _, _ ->
                    ConnectionSettings.setWarned(this)
                    showProxyList()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_proxies, null)
        val chips = view.findViewById<ChipGroup>(R.id.proxy_countries)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.proxy_progress)
        val message = view.findViewById<TextView>(R.id.proxy_message)
        val items = view.findViewById<LinearLayout>(R.id.proxy_items)
        var loadGen = 0

        fun pick(p: PublicProxy) {
            sheet.dismiss()
            ConnectionSettings.setProxy(this, p)
            ConnectionSettings.setMode(this, ConnectionSettings.Mode.PROXY)
            toast(getString(R.string.proxy_selected, p.label))
            if (SshTunnel.isEnabled) SshTunnel.restart(this) else connectTunnel()
        }

        fun show(list: List<PublicProxy>) {
            items.removeAllViews()
            val selected = ConnectionSettings.proxy(this)
            for (p in list) {
                val item = layoutInflater.inflate(R.layout.item_video, items, false) as MaterialCardView
                item.findViewById<TextView>(R.id.video_kind).text = p.protocol.uppercase()
                item.findViewById<TextView>(R.id.video_title).text =
                    listOf(flag(p.country), p.city.ifEmpty { p.country }).filter { it.isNotEmpty() }.joinToString(" ")
                        .ifEmpty { p.ip }
                item.findViewById<TextView>(R.id.video_host).text = listOfNotNull(
                    p.address,
                    anonymityLabel(p.anonymity).ifEmpty { null },
                    if (p.uptime > 0) getString(R.string.proxy_uptime, p.uptime) else null,
                ).joinToString(" · ")
                item.findViewById<TextView>(R.id.video_duration).apply {
                    if (p.timeoutMs > 0) {
                        text = getString(R.string.proxy_ms, p.timeoutMs)
                        val c = when {
                            p.timeoutMs < 1_500 -> R.color.tunnel_ok
                            p.timeoutMs < 4_000 -> R.color.tunnel_wait
                            else -> R.color.tunnel_error
                        }
                        setTextColor(ContextCompat.getColor(context, c))
                    }
                }
                if (selected != null && p.ip == selected.ip && p.port == selected.port) {
                    item.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                    item.strokeColor = MaterialColors.getColor(item, com.google.android.material.R.attr.colorPrimary)
                }
                item.setOnClickListener { pick(p) }
                items.addView(item)
            }
        }

        // Only proxies that pass a real check on the phone are shown, fastest first, as they come in
        fun load(country: String) {
            val gen = ++loadGen
            val cancelled = { gen != loadGen || !sheet.isShowing }
            progress.visibility = View.VISIBLE
            message.visibility = View.GONE
            items.removeAllViews()
            thread {
                val candidates = runCatching { PublicProxies.candidates(country) }.getOrElse { e ->
                    runOnUiThread {
                        if (cancelled()) return@runOnUiThread
                        progress.visibility = View.GONE
                        message.visibility = View.VISIBLE
                        message.text = getString(R.string.proxy_load_failed, e.message)
                    }
                    return@thread
                }
                val found = ArrayList<PublicProxy>()
                fun status() {
                    message.visibility = View.VISIBLE
                    message.text = getString(R.string.proxy_checking, candidates.size, found.size)
                }
                runOnUiThread { if (!cancelled()) status() }
                if (candidates.isEmpty()) {
                    runOnUiThread {
                        if (cancelled()) return@runOnUiThread
                        progress.visibility = View.GONE
                        message.setText(R.string.proxy_empty)
                    }
                    return@thread
                }
                PublicProxies.verify(candidates, cancelled) { p ->
                    runOnUiThread {
                        if (cancelled()) return@runOnUiThread
                        found += p
                        found.sortBy { it.timeoutMs }
                        show(found)
                        status()
                    }
                }
                runOnUiThread {
                    if (cancelled()) return@runOnUiThread
                    progress.visibility = View.GONE
                    if (found.isEmpty()) message.setText(R.string.proxy_none_working) else message.visibility = View.GONE
                }
            }
        }

        val countries = listOf("ua", "pl", "de", "nl", "us", "")
        var country = ConnectionSettings.country(this).takeIf { it in countries } ?: "ua"
        for (c in countries) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = if (c.isEmpty()) getString(R.string.proxy_all) else "${flag(c)} ${c.uppercase()}"
                isCheckable = true
                isChecked = c == country
                setOnClickListener {
                    if (c != country) {
                        country = c
                        ConnectionSettings.setCountry(this@MainActivity, c)
                        load(c)
                    }
                }
            }
            chips.addView(chip)
        }
        view.findViewById<Button>(R.id.proxy_refresh).setOnClickListener { load(country) }

        sheet.setContentView(view)
        sheet.show()
        load(country)
    }

    /** "ua" → 🇺🇦 */
    private fun flag(code: String): String {
        if (code.length != 2) return ""
        return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun anonymityLabel(a: String) = when (a.lowercase()) {
        "transparent" -> getString(R.string.anon_transparent)
        "anonymous" -> getString(R.string.anon_anonymous)
        "elite" -> getString(R.string.anon_elite)
        else -> a
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
        // "video_720p.mp4", "/1080/", "-480p-" — only with separators, so ids like 7201 don't match
        private val urlHeight = Regex("[_\\-./=](2160|1440|1080|720|576|480|360|240)p?(?=[_\\-./?&]|$)", RegexOption.IGNORE_CASE)
    }
}
