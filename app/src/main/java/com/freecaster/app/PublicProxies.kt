package com.freecaster.app

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A free proxy from proxyscrape.com. [protocol]: http, socks4 or socks5. */
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
    private const val API = "https://api.proxyscrape.com/v4/free-proxy-list/get" +
        "?request=get_proxies&proxy_format=protocolipport&format=json&limit=30&skip=0"

    /** Last fetched list, used to switch to another proxy when the current one dies. */
    @Volatile var cached: List<PublicProxy> = emptyList()
        private set

    /**
     * Blocking. Always direct (like updates): the list is needed exactly when the tunnel is broken.
     * Keeps only live proxies that can carry HTTPS, fastest first.
     */
    fun fetch(country: String): List<PublicProxy> {
        val url = if (country.isEmpty()) API else "$API&country=$country"
        val json = StreamProxy.direct.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string() ?: throw IOException("empty response"))
        }
        val arr = json.optJSONArray("proxies") ?: return emptyList()
        val out = ArrayList<PublicProxy>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val protocol = o.optString("protocol").lowercase()
            if (!o.optBoolean("alive")) continue
            if (protocol !in setOf("http", "socks4", "socks5")) continue
            // A plain HTTP proxy without CONNECT support can't open HTTPS sites
            if (protocol == "http" && !o.optBoolean("ssl")) continue
            val geo = o.optJSONObject("ip_data")
            out += PublicProxy(
                protocol = protocol,
                ip = o.optString("ip"),
                port = o.optInt("port"),
                city = geo?.optString("city").orEmpty(),
                country = geo?.optString("countryCode").orEmpty(),
                anonymity = o.optString("anonymity"),
                timeoutMs = o.optDouble("timeout", 0.0).toInt(),
                uptime = o.optDouble("uptime", 0.0).toInt(),
            )
        }
        out.sortBy { if (it.timeoutMs > 0) it.timeoutMs else Int.MAX_VALUE }
        cached = out
        return out
    }

    /** Opens a TCP connection to [host]:[port] through [p] (HTTP CONNECT, SOCKS4 or SOCKS5). */
    fun dial(p: PublicProxy, host: String, port: Int): Upstream {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(p.ip, p.port), 10_000)
            s.soTimeout = 15_000 // only for the handshake
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            when (p.protocol) {
                "socks5" -> socks5(inp, out, host, port)
                "socks4" -> socks4(inp, out, host, port)
                else -> httpConnect(inp, out, host, port)
            }
            s.soTimeout = 0
            return Upstream(inp, out) { runCatching { s.close() } }
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
