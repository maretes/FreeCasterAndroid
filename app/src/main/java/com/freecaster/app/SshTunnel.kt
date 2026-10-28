package com.freecaster.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * SSH-тунель для браузера, аналог `ssh -D`: локальний SOCKS-сервер,
 * з'єднання якого йдуть каналами direct-tcpip через SSH.
 *
 * Kill switch: поки тунель увімкнено, WebView і StreamProxy ходять лише через
 * SOCKS. Якщо SSH відпав, нові з'єднання чекають на перепідключення (до [WAIT_MS])
 * і ніколи не йдуть напряму.
 */
object SshTunnel {
    enum class State { OFF, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    /** [since]: elapsedRealtime, коли почався поточний стан. */
    data class Status(
        val state: State,
        val since: Long = SystemClock.elapsedRealtime(),
        val message: String? = null,
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
    private val traffic = AtomicLong()

    val isEnabled: Boolean get() = enabled

    /** Порт локального SOCKS, поки тунель увімкнено (навіть під час перепідключення). */
    val socksPort: Int? get() = if (enabled) socks?.port else null

    /** Скільки байтів пройшло через тунель з моменту увімкнення. */
    val trafficBytes: Long get() = traffic.get()

    fun addListener(l: (Status) -> Unit) = listeners.add(l)
    fun removeListener(l: (Status) -> Unit) = listeners.remove(l)

    /** [onReady] викликається на головному потоці, коли WebView уже ходить через тунель. */
    @Synchronized
    fun start(ctx: Context, onReady: (() -> Unit)? = null): Boolean {
        val app = ctx.applicationContext
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        if (enabled) {
            onReady?.let { main.post(it) }
            return true
        }
        val cfg = SshSettings.load(app) ?: return false
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
        launchWorker(cfg, SshSettings.knownHosts(app))
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
        socks?.close()
        socks = null
        val exec = ContextCompat.getMainExecutor(app)
        ProxyController.getInstance().clearProxyOverride(exec) { onDone?.invoke() }
        setStatus(Status(State.OFF))
        TunnelService.stop(app)
    }

    /** Нові дані сервера. SOCKS-порт і проксі WebView не змінюються, тож нічого не "протікає". */
    @Synchronized
    fun restart(ctx: Context) {
        if (!enabled) return
        val app = ctx.applicationContext
        val cfg = SshSettings.load(app) ?: return
        generation++
        synchronized(lock) { lock.notifyAll() }
        dropSession()
        setStatus(Status(State.CONNECTING))
        launchWorker(cfg, SshSettings.knownHosts(app))
    }

    /** Кнопка "Повторити" після помилки. */
    fun retry(ctx: Context) = restart(ctx)

    /**
     * Змінилась мережа (Wi-Fi ↔ мобільна). Старий сокет уже мертвий,
     * тож одразу перепідключаємось, не чекаючи keepalive.
     */
    fun onNetworkChanged(changed: Boolean) {
        if (!enabled) return
        // Зміна мережі рве живу сесію; поява мережі під час очікування — пробуємо одразу
        if (changed || session == null) kick = true
        synchronized(lock) { lock.notifyAll() }
    }

    // ---------------- Робочий потік ----------------

    // disconnect() пише в сокет, тож не на головному потоці
    private fun dropSession() {
        val s = session ?: return
        session = null
        thread(isDaemon = true) { s.disconnect() }
    }

    private fun launchWorker(cfg: SshConfig, knownHosts: File) {
        val gen = generation
        host = cfg.host
        kick = false
        thread(name = "ssh-tunnel", isDaemon = true) { work(gen, cfg, knownHosts) }
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
                    // Неправильний пароль чи підмінений сервер: не довбимо його повторами
                    setStatus(Status(State.ERROR, message = fatal, hostKeyChanged = isHostKeyChanged(e)), gen)
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
                // restart()/stop() могли статися, поки ми підключались
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
            setStatus(Status(State.CONNECTED, message = "Підключено до ${cfg.host}"), gen)

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
        // Перший раз запам'ятовуємо ключ сервера, далі не пускаємо, якщо його підмінили
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(2)
        s.connect(CONNECT_TIMEOUT)
        return s
    }

    private fun fatalError(e: Exception): String? {
        val msg = e.message ?: ""
        return when {
            isHostKeyChanged(e) -> "Ключ SSH-сервера змінився. Якщо ви не перевстановлювали сервер, це може бути підміна."
            msg.contains("Auth fail") || msg.contains("Auth cancel") -> "Неправильний користувач або пароль"
            else -> null
        }
    }

    private fun isHostKeyChanged(e: Exception) = e.message?.contains("HostKey has been changed") == true

    private fun transientError(e: Exception): String = when (e.cause ?: e) {
        is UnknownHostException -> "Сервер не знайдено або немає інтернету"
        is ConnectException -> "Сервер не приймає з'єднання"
        is SocketTimeoutException -> "Сервер не відповідає"
        else -> "Немає з'єднання з сервером"
    }

    private class Prompter(private val pass: String) : UserInfo, UIKeyboardInteractive {
        private var kbdAsked = false
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?) = false // без повторів з тим самим паролем
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = true // лише новий сервер (TOFU); змінений ключ JSch відхиляє сам
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

    // ---------------- Канали для SOCKS ----------------

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

    private fun openUpstream(host: String, port: Int): Upstream {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (true) {
            val s = awaitSession(deadline) ?: throw IOException("SSH-тунель недоступний")
            val ch = try {
                s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            } catch (e: JSchException) {
                if (s.isConnected || SystemClock.elapsedRealtime() >= deadline) throw IOException(e)
                continue
            }
            ch.setHost(host)
            ch.setPort(port)
            // Потоки беремо до connect, щоб не загубити перші байти від сервера
            val input = ch.inputStream
            val output = ch.outputStream
            try {
                ch.connect(10_000)
            } catch (e: JSchException) {
                ch.disconnect()
                // Сесія щойно впала: чекаємо нову, сторінка просто довше вантажиться
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
            // Старіші WebView знають лише "socks"; наш сервер розуміє і SOCKS4, і SOCKS5
            ProxyController.getInstance().setProxyOverride(config("socks"), exec, done)
        }
    }

    // ---------------- Стан ----------------

    /** Не скидаємо таймер, поки триває той самий стан: UI рахує, скільки ми вже без з'єднання. */
    private fun keepSince(state: State, message: String): Status {
        val cur = status
        return if (cur.state == state) cur.copy(message = message) else Status(state, message = message)
    }

    private fun setStatus(s: Status, gen: Int? = null) {
        synchronized(this) {
            if (gen != null && gen != generation) return
            status = s
        }
        main.post { listeners.forEach { it(s) } }
    }

    fun describe(s: Status = status): String = when (s.state) {
        State.OFF -> "Вимкнено"
        State.CONNECTING -> s.message?.let { "Підключення… ($it)" } ?: "Підключення…"
        State.CONNECTED -> "Підключено до $host"
        State.RECONNECTING -> s.message?.let { "Перепідключення… ($it)" } ?: "Перепідключення…"
        State.ERROR -> s.message ?: "Помилка"
    }
}
