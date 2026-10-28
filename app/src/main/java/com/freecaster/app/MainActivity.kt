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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private var castContext: CastContext? = null

    private val videos = LinkedHashMap<String, DetectedVideo>()
    private val probed = HashSet<String>()
    private var pageGen = 0 // щоб результати перевірки старої сторінки не потрапили в нову
    @Volatile private var adBlock = true
    @Volatile private var userAgent = ""

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }

    private val tunnelListener: (SshTunnel.Status) -> Unit = { st ->
        // Помилку показуємо один раз, коли вона з'явилась
        if (st.state == SshTunnel.State.ERROR && lastTunnelState != SshTunnel.State.ERROR) {
            toast("SSH: ${SshTunnel.describe(st)}")
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
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.progress)

        castContext = try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            toast("Google Cast недоступний на цьому пристрої")
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

        // Тунель був увімкнений: спершу проксі для WebView, потім перша сторінка (kill switch)
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

    /** Посилання, яким поділились з іншого додатка або відкрили через нас. */
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

    // ---------------- Браузер ----------------

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
            setSupportMultipleWindows(true) // разом з onCreateWindow=false блокує попапи
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
                if (scheme != "http" && scheme != "https") return true // intent://, market:// — часто реклама
                if (adBlock && AdBlocker.isAd(req.url.host)) return true
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                clearVideos()
                if (!address.hasFocus()) address.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!docStart) view.evaluateJavascript(VideoDetector.SCAN_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!address.hasFocus()) address.setText(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progress.setProgressCompat(newProgress, true)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
                false // блокуємо спливаючі вікна

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

    private fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }

    // ---------------- Знайдені відео ----------------

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
        fab.text = if (main > 0) "Відео: $main" else "Короткі: ${videos.size}"
    }

    private fun formatDuration(d: Double?): String? {
        if (d == null) return null
        if (d == VideoProbe.LIVE) return "LIVE"
        val t = d.toLong()
        val h = t / 3600
        val m = t % 3600 / 60
        val s = t % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun showVideoList(showShort: Boolean = false) {
        // Пряма трансляція і найдовші відео вгору (зазвичай це саме те, що треба),
        // потім невідомої тривалості за типом: HLS, MP4…
        val all = videos.values.sortedWith(
            compareByDescending<DetectedVideo> { v ->
                val d = v.duration ?: -2.0
                if (d == VideoProbe.LIVE) Double.MAX_VALUE else d
            }.thenBy { it.kind.ordinal }
        )
        val shortCount = all.count { it.isShort }
        val list = if (showShort) all else all.filter { !it.isShort }
        if (list.isEmpty() && !showShort) return showVideoList(showShort = true)
        val labels = list.map { v ->
            val u = Uri.parse(v.url)
            val name = u.lastPathSegment?.take(50) ?: v.url.take(50)
            val info = listOfNotNull(v.kind.label, formatDuration(v.duration), u.host).joinToString(" · ")
            "$info\n$name"
        }.toTypedArray()
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("Знайдені відео")
            .setItems(labels) { _, i -> showVideoActions(list[i]) }
            .setNegativeButton("Закрити", null)
        // Короткі (менше хвилини) зазвичай реклама, ховаємо їх
        if (!showShort && shortCount > 0) {
            b.setNeutralButton("Короткі ($shortCount)") { _, _ -> showVideoList(showShort = true) }
        }
        b.show()
    }

    private fun showVideoActions(v: DetectedVideo) {
        val actions = arrayOf(
            "📺 Транслювати на Chromecast",
            "📡 Через телефон (якщо не грає)",
            "▶️ Відкрити в плеєрі на телефоні",
            "📋 Копіювати посилання",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(v.kind.label)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> cast(v, viaPhone = false)
                    1 -> cast(v, viaPhone = true)
                    2 -> openExternal(v)
                    3 -> copy(v.url)
                }
            }
            .show()
    }

    // ---------------- Chromecast ----------------

    private fun cast(v: DetectedVideo, viaPhone: Boolean) {
        // Chromecast сам у тунель не потрапить, тож під тунелем відео йде лише через телефон
        if (!viaPhone && SshTunnel.isEnabled) return cast(v, viaPhone = true)
        val session = castContext?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        if (session == null || !session.isConnected || client == null) {
            toast("Спочатку натисніть значок трансляції вгорі й виберіть Chromecast")
            return
        }
        val url = if (viaPhone) {
            val ip = NetUtils.localIpv4(this) ?: run {
                toast("Телефон має бути в тій самій Wi-Fi мережі, що й Chromecast")
                return
            }
            ProxyService.start(this)
            StreamProxy.proxify(ip, v.url, v.headers)
        } else v.url

        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, web.title ?: "Відео")
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
                toast("Chromecast не зміг відкрити відео. Пробую через телефон…")
                cast(v, viaPhone = true)
            } else {
                toast("Не вдалося відтворити (код ${result.status.statusCode})")
            }
        }
        if (!viaPhone) ProxyService.stop(this)
    }

    private fun openExternal(v: DetectedVideo) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.url), v.kind.mime))
        } catch (e: Exception) {
            toast("Немає плеєра для цього відео")
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("video", text))
        toast("Скопійовано")
    }

    // ---------------- Меню ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        menu.findItem(R.id.action_tunnel)?.actionView?.let { v ->
            tunnelIcon = v.findViewById(R.id.tunnel_icon)
            tunnelTraffic = v.findViewById(R.id.tunnel_traffic)
            v.setOnClickListener { onTunnelClick() }
            // Довге натискання: стан, налаштування, "Повторити"
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

    // ---------------- Оновлення ----------------

    private fun checkUpdates() {
        toast("Перевіряю оновлення…")
        thread {
            val rel = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val r = rel.getOrElse {
                    toast("Не вдалося перевірити оновлення: ${it.message}")
                    return@runOnUiThread
                }
                val current = Updater.currentBuild(this)
                if (r.build <= current) {
                    toast("У вас остання версія (збірка $current)")
                    return@runOnUiThread
                }
                val size = if (r.size > 0) " · ${formatBytes(r.size)}" else ""
                MaterialAlertDialogBuilder(this)
                    .setTitle("Доступна збірка ${r.build}")
                    .setMessage("Зараз встановлена збірка $current$size\n\n${r.notes.ifEmpty { "Без опису" }}")
                    .setPositiveButton("Оновити") { _, _ -> startUpdate(r) }
                    .setNegativeButton("Пізніше", null)
                    .show()
            }
        }
    }

    private fun startUpdate(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Дозвіл дається один раз, далі оновлення в один дотик
            toast("Дозвольте FreeCaster встановлювати додатки, поверніться і натисніть «Оновити» ще раз")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            setPadding(64, 32, 64, 0)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Завантаження збірки ${r.build}")
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
                    toast("Помилка оновлення: ${e.message}")
                }
            }
        }
    }

    // ---------------- SSH-тунель ----------------

    private fun onTunnelClick() {
        when {
            SshSettings.load(this) == null -> showSshSettings(connectAfter = true)
            !SshTunnel.isEnabled -> connectTunnel()
            // Тут без підтвердження не можна: треба вирішити, чи довіряти новому ключу
            SshTunnel.status.hostKeyChanged -> showTunnelStatus()
            // Звичайний перемикач: вимикаємо одразу, без діалогу
            else -> SshTunnel.stop(this) { web.reload() }
        }
    }

    private fun connectTunnel() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            toast("WebView на телефоні не підтримує проксі. Оновіть Android System WebView")
            return
        }
        if (!SshTunnel.start(this) { web.reload() }) toast("Не вдалося запустити тунель")
    }

    private fun showTunnelStatus() {
        val st = SshTunnel.status
        val cfg = SshSettings.load(this)
        val msg = SshTunnel.describe(st) + (cfg?.let { "\n\n${it.user}@${it.host}:${it.port}" } ?: "")
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("SSH-тунель")
            .setMessage(msg)
            .setNeutralButton("Налаштування") { _, _ -> showSshSettings(connectAfter = false) }
        if (SshTunnel.isEnabled) {
            b.setNegativeButton("Відключити") { _, _ -> SshTunnel.stop(this) { web.reload() } }
        }
        if (st.state == SshTunnel.State.ERROR) {
            b.setPositiveButton(if (st.hostKeyChanged) "Довіряти новому ключу" else "Повторити") { _, _ ->
                if (st.hostKeyChanged) SshSettings.forgetHostKey(this)
                SshTunnel.retry(this)
            }
        } else {
            b.setPositiveButton("Закрити", null)
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
            .setTitle("SSH-сервер")
            .setView(view)
            .setPositiveButton("Зберегти") { _, _ ->
                val cfg = SshSettings.parse(
                    host.text.toString(), port.text.toString(), user.text.toString(), pass.text.toString()
                )
                if (cfg == null) {
                    toast("Вкажіть сервер, користувача і пароль")
                    return@setPositiveButton
                }
                SshSettings.save(this, cfg)
                when {
                    SshTunnel.isEnabled -> SshTunnel.restart(this)
                    connectAfter -> connectTunnel()
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    /**
     * Сіра: вимкнено; зелена: працює (і перші 10 с тихого перепідключення);
     * жовта: підключаємось; червона: помилка або сервер недоступний понад хвилину.
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

        // Поки тунель увімкнено, щосекунди оновлюємо лічильник і колір
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

    // ---------------- Повний екран у браузері ----------------

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
