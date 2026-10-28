package com.freecaster.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Tunnel for the browser: a local SOCKS server whose connections go either through
 * SSH as direct-tcpip channels (like `ssh -D`) or through a public proxy from a list.
 *
 * Kill switch: while the tunnel is on, the WebView and StreamProxy only go through
 * SOCKS. If SSH drops, new connections wait for the reconnect (up to [WAIT_MS])
 * and never go direct.
 */
object SshTunnel {
    enum class State { OFF, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    /** [since]: elapsedRealtime when the current state began; [reason]: string resource. */
    data class Status(
        val state: State,
        val since: Long = SystemClock.elapsedRealtime(),
        @StringRes val reason: Int? = null,
        val hostKeyChanged: Boolean = false,
    )

    private const val WAIT_MS = 15_000L
    private const val CONNECT_TIMEOUT = 15_000
    private val BACKOFF = longArrayOf(0, 1_000, 2_000, 5_000, 10_000, 20_000, 30_000)

    @Volatile var status = Status(State.OFF)
        private set
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val lock = Object()
    @Volatile private var enabled = false
    @Volatile private var generation = 0
    @Volatile private var session: Session? = null
    @Volatile private var socks: SocksServer? = null
    @Volatile private var kick = false
    @Volatile private var host = ""
    /** Public proxy mode: the proxy in use, and whether its last health check passed. */
    @Volatile private var proxy: PublicProxy? = null
    @Volatile private var proxyUp = false
    private val traffic = AtomicLong()

    val isEnabled: Boolean get() = enabled

    /** Local SOCKS port while the tunnel is on (even while reconnecting). */
    val socksPort: Int? get() = if (enabled) socks?.port else null

    /** Bytes sent through the tunnel since it was turned on. */
    val trafficBytes: Long get() = traffic.get()

    fun addListener(l: (Status) -> Unit) = listeners.add(l)
    fun removeListener(l: (Status) -> Unit) = listeners.remove(l)

    /** [onReady] runs on the main thread once the WebView goes through the tunnel. */
    @Synchronized
    fun start(ctx: Context, onReady: (() -> Unit)? = null): Boolean {
        val app = ctx.applicationContext
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        if (enabled) {
            onReady?.let { main.post(it) }
            return true
        }
        if (!ConnectionSettings.isConfigured(app)) return false
        val srv = try {
            SocksServer(::openUpstream, traffic).also { it.start() }
        } catch (e: IOException) {
            return false
        }
        socks = srv
        traffic.set(0)
        enabled = true
        SshSettings.setEnabled(app, true)
        setStatus(Status(State.CONNECTING))
        applyWebViewProxy(app, srv.port, onReady)
        if (!launchWorker(app)) {
            stop(app)
            return false
        }
        TunnelService.start(app)
        return true
    }

    @Synchronized
    fun stop(ctx: Context, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        SshSettings.setEnabled(app, false)
        if (!enabled) {
            onDone?.let { main.post(it) }
            return
        }
        enabled = false
        generation++
        synchronized(lock) { lock.notifyAll() }
        dropSession()
        proxyUp = false
        socks?.close()
        socks = null
        val exec = ContextCompat.getMainExecutor(app)
        ProxyController.getInstance().clearProxyOverride(exec) { onDone?.invoke() }
        setStatus(Status(State.OFF))
        TunnelService.stop(app)
    }

    /** New server settings. The SOCKS port and WebView proxy stay the same, so nothing leaks. */
    @Synchronized
    fun restart(ctx: Context) {
        if (!enabled) return
        val app = ctx.applicationContext
        if (!ConnectionSettings.isConfigured(app)) return
        generation++
        synchronized(lock) { lock.notifyAll() }
        dropSession()
        proxyUp = false
        setStatus(Status(State.CONNECTING))
        launchWorker(app)
    }

    /** The "Retry" button after an error. */
    fun retry(ctx: Context) = restart(ctx)

    /**
     * The network changed (Wi-Fi ↔ mobile). The old socket is already dead,
     * so reconnect right away instead of waiting for keepalive.
     */
    fun onNetworkChanged(changed: Boolean) {
        if (!enabled) return
        // A network change kills a live session; a network appearing during backoff retries at once
        if (changed || session == null || proxy != null) kick = true
        synchronized(lock) { lock.notifyAll() }
    }

    // ---------------- Worker thread ----------------

    // disconnect() writes to the socket, so keep it off the main thread
    private fun dropSession() {
        val s = session ?: return
        session = null
        thread(isDaemon = true) { s.disconnect() }
    }

    private fun launchWorker(app: Context): Boolean {
        val gen = generation
        kick = false
        if (ConnectionSettings.mode(app) == ConnectionSettings.Mode.PROXY) {
            val p = ConnectionSettings.proxy(app) ?: return false
            proxy = p
            proxyUp = false
            host = p.label
            thread(name = "proxy-tunnel", isDaemon = true) { workProxy(gen, p, app) }
        } else {
            val cfg = SshSettings.load(app) ?: return false
            proxy = null
            host = cfg.host
            thread(name = "ssh-tunnel", isDaemon = true) { work(gen, cfg, SshSettings.knownHosts(app)) }
        }
        return true
    }

    /**
     * Public proxy: check every 30 s that it can reach the internet. After 3 failed checks in a row
     * switch to the next proxy from the last fetched list, so a dead free proxy is barely noticed.
     */
    private fun workProxy(gen: Int, first: PublicProxy, app: Context) {
        var p = first
        var fails = 0
        var attempt = 0
        var connectedOnce = false
        val spares = PublicProxies.cached.filter { it != first }.toMutableList()
        while (alive(gen)) {
            val ok = try {
                PublicProxies.dial(p, "www.google.com", 443).close()
                true
            } catch (e: Exception) {
                false
            }
            if (!alive(gen)) return
            if (ok) {
                synchronized(lock) {
                    proxyUp = true
                    lock.notifyAll()
                }
                fails = 0
                attempt = 0
                connectedOnce = true
                setStatus(Status(State.CONNECTED), gen)
                val until = SystemClock.elapsedRealtime() + 30_000
                waitWhile { alive(gen) && !kick && SystemClock.elapsedRealtime() < until }
                kick = false
                continue
            }
            proxyUp = false
            fails++
            if (fails >= 3 && spares.isNotEmpty()) {
                p = spares.removeAt(0)
                ConnectionSettings.setProxy(app, p)
                proxy = p
                host = p.label
                fails = 0
                continue // try the new one right away
            }
            setStatus(keepSince(if (connectedOnce) State.RECONNECTING else State.CONNECTING, R.string.err_proxy_dead), gen)
            val delay = BACKOFF[attempt.coerceAtMost(BACKOFF.size - 1)].coerceAtLeast(1_000)
            attempt++
            val until = SystemClock.elapsedRealtime() + delay
            waitWhile { alive(gen) && !kick && SystemClock.elapsedRealtime() < until }
            kick = false
        }
    }

    private fun alive(gen: Int) = enabled && gen == generation

    private fun work(gen: Int, cfg: SshConfig, knownHosts: File) {
        var attempt = 0
        var connectedOnce = false
        while (alive(gen)) {
            val s = try {
                connect(cfg, knownHosts)
            } catch (e: Exception) {
                if (!alive(gen)) return
                val fatal = fatalError(e)
                if (fatal != null) {
                    // Wrong password or a spoofed server: don't hammer it with retries
                    setStatus(Status(State.ERROR, reason = fatal, hostKeyChanged = isHostKeyChanged(e)), gen)
                    waitWhile { alive(gen) }
                    return
                }
                val state = if (connectedOnce) State.RECONNECTING else State.CONNECTING
                setStatus(keepSince(state, transientError(e)), gen)
                val delay = BACKOFF[attempt.coerceAtMost(BACKOFF.size - 1)].coerceAtLeast(1_000)
                attempt++
                val until = SystemClock.elapsedRealtime() + delay
                waitWhile { alive(gen) && !kick && SystemClock.elapsedRealtime() < until }
                kick = false
                continue
            }

            synchronized(lock) {
                // restart()/stop() may have happened while we were connecting
                if (!alive(gen)) {
                    thread(isDaemon = true) { s.disconnect() }
                    return
                }
                session = s
                lock.notifyAll()
            }
            connectedOnce = true
            attempt = 0
            kick = false
            setStatus(Status(State.CONNECTED), gen)

            waitWhile { alive(gen) && s.isConnected && !kick }

            synchronized(lock) { if (session === s) session = null }
            s.disconnect()
            if (alive(gen)) setStatus(Status(State.RECONNECTING), gen)
        }
    }

    private fun waitWhile(cond: () -> Boolean) {
        synchronized(lock) {
            while (cond()) {
                try {
                    lock.wait(1_000)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun connect(cfg: SshConfig, knownHosts: File): Session {
        if (!knownHosts.exists()) knownHosts.createNewFile()
        val jsch = JSch()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
        s.setPassword(cfg.password)
        s.userInfo = Prompter(cfg.password)
        // Remember the server key the first time, refuse to connect if it changes later
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(2)
        s.connect(CONNECT_TIMEOUT)
        return s
    }

    @StringRes
    private fun fatalError(e: Exception): Int? {
        val msg = e.message ?: ""
        return when {
            isHostKeyChanged(e) -> R.string.err_host_key
            msg.contains("Auth fail") || msg.contains("Auth cancel") -> R.string.err_auth
            else -> null
        }
    }

    private fun isHostKeyChanged(e: Exception) = e.message?.contains("HostKey has been changed") == true

    @StringRes
    private fun transientError(e: Exception): Int = when (e.cause ?: e) {
        is UnknownHostException -> R.string.err_unknown_host
        is ConnectException -> R.string.err_refused
        is SocketTimeoutException -> R.string.err_timeout
        else -> R.string.err_no_connection
    }

    private class Prompter(private val pass: String) : UserInfo, UIKeyboardInteractive {
        private var kbdAsked = false
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?) = false // no retries with the same password
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = true // only for a new server (TOFU); JSch rejects a changed key itself
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<String>?, echo: BooleanArray?,
        ): Array<String>? {
            if (kbdAsked || prompt == null) return null
            kbdAsked = true
            return Array(prompt.size) { pass }
        }
    }

    // ---------------- Channels for SOCKS ----------------

    private fun awaitSession(deadline: Long): Session? {
        synchronized(lock) {
            while (true) {
                val s = session
                if (s != null && s.isConnected) return s
                if (!enabled) return null
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                lock.wait(left)
            }
        }
    }

    private fun openUpstream(host: String, port: Int): Upstream =
        if (proxy != null) openViaProxy(host, port) else openViaSsh(host, port)

    /** Waits (kill switch) while the proxy is being checked or replaced, then dials through it. */
    private fun openViaProxy(host: String, port: Int): Upstream {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        synchronized(lock) {
            while (!proxyUp) {
                if (!enabled) throw IOException("tunnel is off")
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw IOException("proxy unavailable")
                lock.wait(left)
            }
        }
        val p = proxy ?: throw IOException("tunnel is off")
        return try {
            PublicProxies.dial(p, host, port)
        } catch (e: IOException) {
            // Maybe the proxy just died: let the worker re-check it now instead of in 30 s
            kick = true
            synchronized(lock) { lock.notifyAll() }
            throw e
        }
    }

    private fun openViaSsh(host: String, port: Int): Upstream {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (true) {
            val s = awaitSession(deadline) ?: throw IOException("SSH tunnel unavailable")
            val ch = try {
                s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            } catch (e: JSchException) {
                if (s.isConnected || SystemClock.elapsedRealtime() >= deadline) throw IOException(e)
                continue
            }
            ch.setHost(host)
            ch.setPort(port)
            // Grab the streams before connect so the server's first bytes aren't lost
            val input = ch.inputStream
            val output = ch.outputStream
            try {
                ch.connect(10_000)
            } catch (e: JSchException) {
                ch.disconnect()
                // The session just died: wait for a new one, the page just loads a bit longer
                if (s.isConnected || SystemClock.elapsedRealtime() >= deadline) throw IOException(e)
                continue
            }
            return Upstream(input, output) { ch.disconnect() }
        }
    }

    // ---------------- WebView ----------------

    private fun applyWebViewProxy(app: Context, port: Int, onReady: (() -> Unit)?) {
        val exec = ContextCompat.getMainExecutor(app)
        val done = Runnable { onReady?.invoke() }
        fun config(scheme: String) = ProxyConfig.Builder().addProxyRule("$scheme://127.0.0.1:$port").build()
        try {
            ProxyController.getInstance().setProxyOverride(config("socks5"), exec, done)
        } catch (e: IllegalArgumentException) {
            // Older WebViews only know "socks"; our server speaks both SOCKS4 and SOCKS5
            ProxyController.getInstance().setProxyOverride(config("socks"), exec, done)
        }
    }

    // ---------------- State ----------------

    /** Keep the timer while the state is unchanged: the UI counts how long we've been offline. */
    private fun keepSince(state: State, @StringRes reason: Int): Status {
        val cur = status
        return if (cur.state == state) cur.copy(reason = reason) else Status(state, reason = reason)
    }

    private fun setStatus(s: Status, gen: Int? = null) {
        synchronized(this) {
            if (gen != null && gen != generation) return
            status = s
        }
        main.post { listeners.forEach { it(s) } }
    }

    fun describe(ctx: Context, s: Status = status): String {
        val reason = s.reason?.let { ctx.getString(it) }
        return when (s.state) {
            State.OFF -> ctx.getString(R.string.st_off)
            State.CONNECTING -> reason?.let { ctx.getString(R.string.st_connecting_reason, it) }
                ?: ctx.getString(R.string.st_connecting)
            State.CONNECTED -> ctx.getString(R.string.st_connected, host)
            State.RECONNECTING -> reason?.let { ctx.getString(R.string.st_reconnecting_reason, it) }
                ?: ctx.getString(R.string.st_reconnecting)
            State.ERROR -> reason ?: ctx.getString(R.string.st_error)
        }
    }
}
