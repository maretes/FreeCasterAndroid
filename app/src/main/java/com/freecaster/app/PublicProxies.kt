package com.freecaster.app

import android.content.Context
import android.os.SystemClock
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A free proxy from proxyscrape.com or spys.one. [protocol]: http, socks4 or socks5.
 * [timeoutMs] is the latency measured on the phone once verified; [uptime] is 0 when unknown.
 */
data class PublicProxy(
    val protocol: String,
    val ip: String,
    val port: Int,
    val city: String = "",
    val country: String = "",
    val anonymity: String = "",
    val timeoutMs: Int = 0,
    val uptime: Int = 0,
) {
    val address: String get() = "$ip:$port"
    val label: String get() = if (city.isNotEmpty()) "$address ($city)" else address

    fun encode() = listOf(protocol, ip, port, city, country, anonymity, timeoutMs, uptime).joinToString("|")

    companion object {
        fun decode(s: String): PublicProxy? {
            val p = s.split("|")
            if (p.size < 8) return null
            return PublicProxy(p[0], p[1], p[2].toIntOrNull() ?: return null, p[3], p[4], p[5],
                p[6].toIntOrNull() ?: 0, p[7].toIntOrNull() ?: 0)
        }
    }
}

/** How the tunnel connects: through the user's SSH server or through a public proxy. */
object ConnectionSettings {
    enum class Mode { SSH, PROXY }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("conn", Context.MODE_PRIVATE)

    fun mode(ctx: Context): Mode =
        if (prefs(ctx).getString("mode", "ssh") == "proxy") Mode.PROXY else Mode.SSH

    fun setMode(ctx: Context, m: Mode) =
        prefs(ctx).edit().putString("mode", if (m == Mode.PROXY) "proxy" else "ssh").apply()

    fun proxy(ctx: Context): PublicProxy? = prefs(ctx).getString("proxy", null)?.let { PublicProxy.decode(it) }

    fun setProxy(ctx: Context, p: PublicProxy) = prefs(ctx).edit().putString("proxy", p.encode()).apply()

    /** Two-letter country code, or "" for all countries. */
    fun country(ctx: Context): String = prefs(ctx).getString("country", "ua") ?: "ua"

    fun setCountry(ctx: Context, c: String) = prefs(ctx).edit().putString("country", c).apply()

    fun warned(ctx: Context) = prefs(ctx).getBoolean("warned", false)

    fun setWarned(ctx: Context) = prefs(ctx).edit().putBoolean("warned", true).apply()

    /** Whether the chosen mode has everything it needs to connect. */
    fun isConfigured(ctx: Context) = when (mode(ctx)) {
        Mode.SSH -> SshSettings.load(ctx) != null
        Mode.PROXY -> proxy(ctx) != null
    }
}

object PublicProxies {
    private const val PROXYSCRAPE = "https://api.proxyscrape.com/v4/free-proxy-list/get" +
        "?request=get_proxies&proxy_format=protocolipport&format=json&limit=100&skip=0"
    // Plain-text feeds of spys.one (the HTML page hides ports behind obfuscated JS)
    private const val SPYS_HTTP = "https://spys.me/proxy.txt"
    private const val SPYS_SOCKS = "https://spys.me/socks.txt"
    private val spysLine = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d+)\s+([A-Z]{2})-([NAH])!?(?:-(S)!?)?""")

    // Prefilter on what the list itself reports
    private const val MIN_UPTIME = 70
    private const val MAX_REPORTED_MS = 3_000
    // Verification on the phone
    private const val MAX_CANDIDATES = 60
    private const val MAX_MEASURED_MS = 3_000L
    private const val PROBE_HOST = "www.gstatic.com"

    /** Last verified list, used to switch to another proxy when the current one dies. */
    @Volatile var cached: List<PublicProxy> = emptyList()
        private set

    /**
     * Candidates from both sources, merged and deduplicated. Blocking.
     * Always direct (like updates): the list is needed exactly when the tunnel is broken.
     * Fails only if both sources fail.
     */
    fun candidates(country: String): List<PublicProxy> {
        val errors = ArrayList<Exception>()
        fun from(f: () -> List<PublicProxy>) = try {
            f()
        } catch (e: Exception) {
            errors += e
            emptyList()
        }
        val all = from { proxyscrape(country) } + from { spys(SPYS_HTTP, "http", country) } +
            from { spys(SPYS_SOCKS, "socks5", country) }
        if (all.isEmpty() && errors.size == 3) throw errors.first()
        return all.distinctBy { it.address }.take(MAX_CANDIDATES)
    }

    /**
     * Checks every candidate twice in parallel, a few seconds apart, so proxies that only
     * work now and then are dropped. Calls [onFound] (background thread) for each one that
     * passes, with its measured latency. Blocks until done or [cancelled] returns true.
     */
    fun verify(list: List<PublicProxy>, cancelled: () -> Boolean, onFound: (PublicProxy) -> Unit) {
        val good = Collections.synchronizedList(ArrayList<PublicProxy>())
        val pool = Executors.newFixedThreadPool(16)
        for (p in list) {
            pool.execute {
                // Anything escaping a pool thread (e.g. InterruptedException on cancel) would crash the app
                try {
                    if (cancelled()) return@execute
                    val first = probe(p) ?: return@execute
                    Thread.sleep(1_500)
                    if (cancelled()) return@execute
                    val second = probe(p) ?: return@execute
                    val ms = (first + second) / 2
                    if (ms > MAX_MEASURED_MS || cancelled()) return@execute
                    val v = p.copy(timeoutMs = ms.toInt())
                    good += v
                    onFound(v)
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            if (cancelled()) {
                pool.shutdownNow()
                break
            }
        }
        if (good.isNotEmpty()) cached = good.sortedBy { it.timeoutMs }
    }

    /**
     * A real HTTPS request through the proxy: TLS with certificate and host name checks
     * (a proxy that tampers with traffic fails), then /generate_204 must answer 204.
     * Returns the time it took, or null.
     */
    private fun probe(p: PublicProxy): Long? {
        val t0 = SystemClock.elapsedRealtime()
        var raw: Socket? = null
        return try {
            raw = dialSocket(p, PROBE_HOST, 443, connectTimeout = 5_000)
            raw.soTimeout = 5_000
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, PROBE_HOST, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(PROBE_HOST, it.session)) return null
                it.outputStream.write(
                    "GET /generate_204 HTTP/1.1\r\nHost: $PROBE_HOST\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                it.outputStream.flush()
                val status = it.inputStream.bufferedReader(Charsets.US_ASCII).readLine() ?: return null
                if (status.split(" ").getOrNull(1) != "204") return null
            }
            SystemClock.elapsedRealtime() - t0
        } catch (e: Exception) {
            null
        } finally {
            runCatching { raw?.close() }
        }
    }

    private fun proxyscrape(country: String): List<PublicProxy> {
        val url = if (country.isEmpty()) PROXYSCRAPE else "$PROXYSCRAPE&country=$country"
        val json = JSONObject(download(url))
        val arr = json.optJSONArray("proxies") ?: return emptyList()
        val out = ArrayList<PublicProxy>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val protocol = o.optString("protocol").lowercase()
            if (!o.optBoolean("alive")) continue
            if (protocol !in setOf("http", "socks4", "socks5")) continue
            // A plain HTTP proxy without CONNECT support can't open HTTPS sites
            if (protocol == "http" && !o.optBoolean("ssl")) continue
            val uptime = o.optDouble("uptime", 0.0).toInt()
            val reported = o.optDouble("timeout", 0.0).toInt()
            // Drop the ones that are often down or very slow before spending time on them
            if (uptime < MIN_UPTIME || reported <= 0 || reported > MAX_REPORTED_MS) continue
            val geo = o.optJSONObject("ip_data")
            out += PublicProxy(
                protocol = protocol,
                ip = o.optString("ip"),
                port = o.optInt("port"),
                city = geo?.optString("city").orEmpty(),
                country = geo?.optString("countryCode").orEmpty(),
                anonymity = o.optString("anonymity"),
                timeoutMs = reported,
                uptime = uptime,
            )
        }
        return out.sortedBy { it.timeoutMs }
    }

    /** spys.me line: "1.2.3.4:8080 UA-H-S! +" = country, anonymity (N/A/H), "S" = supports HTTPS. */
    private fun spys(url: String, protocol: String, country: String): List<PublicProxy> =
        download(url).lineSequence().mapNotNull { line ->
            val m = spysLine.find(line.trim()) ?: return@mapNotNull null
            val (ip, port, cc, anon, ssl) = m.destructured
            if (country.isNotEmpty() && !cc.equals(country, ignoreCase = true)) return@mapNotNull null
            if (protocol == "http" && ssl.isEmpty()) return@mapNotNull null
            PublicProxy(
                protocol = protocol,
                ip = ip,
                port = port.toInt(),
                country = cc,
                anonymity = when (anon) {
                    "N" -> "transparent"
                    "A" -> "anonymous"
                    else -> "elite"
                },
            )
        }.toList()

    private fun download(url: String): String =
        StreamProxy.direct.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("empty response")
        }

    /** Opens a TCP connection to [host]:[port] through [p] (HTTP CONNECT, SOCKS4 or SOCKS5). */
    fun dial(p: PublicProxy, host: String, port: Int): Upstream {
        val s = dialSocket(p, host, port)
        return Upstream(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() } }
    }

    private fun dialSocket(p: PublicProxy, host: String, port: Int, connectTimeout: Int = 10_000): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(p.ip, p.port), connectTimeout)
            s.soTimeout = 15_000 // only for the handshake
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            when (p.protocol) {
                "socks5" -> socks5(inp, out, host, port)
                "socks4" -> socks4(inp, out, host, port)
                else -> httpConnect(inp, out, host, port)
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun httpConnect(inp: InputStream, out: OutputStream, host: String, port: Int) {
        val target = if (':' in host) "[$host]:$port" else "$host:$port"
        out.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        // Read the response headers byte by byte so no tunneled data is consumed
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw IOException("proxy closed the connection")
            if (head.length > 8192) throw IOException("proxy response too long")
            head.append(b.toChar())
        }
        val code = head.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull()
        if (code != 200) throw IOException("proxy refused CONNECT: ${head.lineSequence().first()}")
    }

    private fun socks5(inp: InputStream, out: OutputStream, host: String, port: Int) {
        out.write(byteArrayOf(5, 1, 0)) // one method: no authentication
        out.flush()
        val greet = readN(inp, 2)
        if (greet[0].toInt() != 5 || greet[1].toInt() != 0) throw IOException("SOCKS5 auth refused")
        val name = host.toByteArray(Charsets.US_ASCII)
        if (name.size > 255) throw IOException("host name too long")
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name +
            byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        val rep = readN(inp, 4)
        if (rep[1].toInt() != 0) throw IOException("SOCKS5 connect failed (${rep[1]})")
        // Skip the bound address
        when (rep[3].toInt()) {
            1 -> readN(inp, 4 + 2)
            3 -> readN(inp, (inp.read() and 0xff) + 2)
            4 -> readN(inp, 16 + 2)
            else -> throw IOException("bad SOCKS5 reply")
        }
    }

    private fun socks4(inp: InputStream, out: OutputStream, host: String, port: Int) {
        // SOCKS4 only takes IPv4, so the name is resolved on the phone
        val ip = InetAddress.getAllByName(host).firstOrNull { it !is Inet6Address }
            ?: throw IOException("no IPv4 address for $host")
        out.write(byteArrayOf(4, 1, (port shr 8).toByte(), port.toByte()) + ip.address + byteArrayOf(0))
        out.flush()
        val rep = readN(inp, 8)
        if (rep[1].toInt() != 0x5A) throw IOException("SOCKS4 connect failed")
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val k = inp.read(b, off, n - off)
            if (k < 0) throw IOException("proxy closed the connection")
            off += k
        }
        return b
    }
}
