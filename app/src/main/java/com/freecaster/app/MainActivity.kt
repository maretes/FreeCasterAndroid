package com.freecaster.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private var castContext: CastContext? = null

    private val videos = LinkedHashMap<String, DetectedVideo>()
    @Volatile private var adBlock = true
    @Volatile private var userAgent = ""

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
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

        if (!handleIntent(intent)) {
            if (savedInstanceState != null) web.restoreState(savedInstanceState)
            else web.loadUrl(prefs.getString("last", HOME) ?: HOME)
        }
    }

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
        fun onVideo(src: String, page: String) {
            if (!src.startsWith("http")) return
            val kind = VideoDetector.classify(src) ?: VideoKind.OTHER
            val headers = buildHeaders(src, mapOf("Referer" to page))
            runOnUiThread { addVideo(DetectedVideo(src, kind, headers)) }
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
        if (existing != null && existing.kind != VideoKind.OTHER) return
        if (existing == null && videos.size >= 60) return
        videos[v.url] = v
        updateFab()
    }

    private fun clearVideos() {
        videos.clear()
        updateFab()
    }

    private fun updateFab() {
        fab.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        fab.text = "Відео: ${videos.size}"
    }

    private fun showVideoList() {
        // HLS та MP4 вгору: зазвичай це саме те, що треба
        val list = videos.values.sortedBy { it.kind.ordinal }
        val labels = list.map { v ->
            val u = Uri.parse(v.url)
            val name = u.lastPathSegment?.take(50) ?: v.url.take(50)
            "${v.kind.label} · ${u.host}\n$name"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Знайдені відео")
            .setItems(labels) { _, i -> showVideoActions(list[i]) }
            .setNegativeButton("Закрити", null)
            .show()
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
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_adblock)?.isChecked = adBlock
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
