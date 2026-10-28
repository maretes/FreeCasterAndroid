package com.freecaster.app

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Маленький HTTP-сервер на телефоні. Chromecast качає відео через нього,
 * а телефон додає потрібні сайту заголовки (Referer, Cookie, User-Agent).
 * HLS-плейлисти переписуються, щоб і сегменти йшли через проксі.
 */
object StreamProxy {
    private var server: Server? = null
    private val headerSets = ConcurrentHashMap<String, Map<String, String>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val uriAttr = Regex("URI=\"([^\"]+)\"")
    private val contentRange = Regex("bytes (\\d+)-(\\d+)/")
    private const val MAX_RESUMES = 6
    @Volatile private var tunneled: Pair<Int, OkHttpClient>? = null

    /** Завжди напряму, повз тунель (для оновлень додатка з GitHub). */
    val direct: OkHttpClient get() = http

    /** Поки SSH-тунель увімкнено, усе йде лише через нього (kill switch). */
    fun client(): OkHttpClient {
        val port = SshTunnel.socksPort ?: return http
        tunneled?.let { (p, c) -> if (p == port) return c }
        val c = http.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .build()
        tunneled = port to c
        return c
    }

    @Synchronized
    fun ensureStarted(): Int {
        server?.let { if (it.isAlive) return it.listeningPort }
        val s = Server()
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        return s.listeningPort
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    /** Повертає адресу виду http://192.168.x.x:port/p?... для Chromecast. */
    fun proxify(phoneIp: String, target: String, headers: Map<String, String>): String {
        val port = ensureStarted()
        val id = Integer.toHexString(headers.hashCode())
        headerSets[id] = headers
        return "http://$phoneIp:$port" + localPath(target, id)
    }

    private fun localPath(target: String, id: String) =
        "/p?h=$id&u=" + URLEncoder.encode(target, "UTF-8")

    private fun rewritePlaylist(text: String, base: HttpUrl, id: String): String {
        fun wrap(ref: String): String =
            base.resolve(ref.trim())?.let { localPath(it.toString(), id) } ?: ref
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"" + wrap(m.groupValues[1]) + "\"" }
                else -> wrap(t)
            }
        }
    }

    fun request(target: String, headers: Map<String, String>, range: String?): Request {
        val req = Request.Builder().url(target)
        headers.forEach { (k, v) -> req.header(k, v) }
        range?.let { req.header("Range", it) }
        return req.build()
    }

    /** Віддає байти [pos]..[end]; при помилці читання відкриває потік заново з [pos]. */
    private class ResumingStream(
        private var cur: InputStream?,
        private var pos: Long,
        private val end: Long,
        private val reopen: (Long) -> InputStream,
    ) : InputStream() {
        private var failures = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos > end) return -1
            while (true) {
                try {
                    val s = cur ?: reopen(pos).also { cur = it }
                    val n = s.read(b, off, minOf(len.toLong(), end - pos + 1).toInt())
                    if (n < 0) throw IOException("premature end of stream")
                    pos += n
                    failures = 0
                    return n
                } catch (e: IOException) {
                    if (++failures > MAX_RESUMES) throw e
                    cur?.let { runCatching { it.close() } }
                    cur = null
                    try {
                        Thread.sleep(1_000L * failures)
                    } catch (ie: InterruptedException) {
                        throw e
                    }
                }
            }
        }

        override fun close() {
            cur?.let { runCatching { it.close() } }
            cur = null
        }
    }

    private class Status(private val code: Int, private val text: String) : Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }

    private class Server : NanoHTTPD(null, 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.OPTIONS) return cors(text(200, ""))
            val target = session.parameters["u"]?.firstOrNull() ?: return cors(text(404, "no url"))
            val id = session.parameters["h"]?.firstOrNull() ?: ""
            val headers = headerSets[id] ?: emptyMap()

            val resp = try {
                client().newCall(request(target, headers, session.headers["range"])).execute()
            } catch (e: Exception) {
                return cors(text(502, "upstream error: ${e.message}"))
            }
            val body = resp.body ?: return cors(text(502, "empty body"))
            val type = resp.header("Content-Type") ?: ""
            val status = Status(resp.code, resp.message.ifEmpty { "OK" })

            val looksLikePlaylist = type.contains("mpegurl", true) ||
                resp.request.url.encodedPath.endsWith(".m3u8", true) ||
                target.contains(".m3u8", true)

            if (looksLikePlaylist && resp.isSuccessful) {
                val bytes = body.bytes()
                val txt = String(bytes, Charsets.UTF_8)
                if (txt.trimStart().startsWith("#EXTM3U")) {
                    val out = rewritePlaylist(txt, resp.request.url, id).toByteArray()
                    return cors(
                        newFixedLengthResponse(status, "application/vnd.apple.mpegurl", ByteArrayInputStream(out), out.size.toLong())
                    )
                }
                return cors(newFixedLengthResponse(status, type.ifEmpty { "application/octet-stream" }, ByteArrayInputStream(bytes), bytes.size.toLong()))
            }

            val mime = type.ifEmpty { "video/mp4" }
            val len = body.contentLength()
            val stream = resumable(resp, body.byteStream(), len, target, headers)
            val r = if (len >= 0) newFixedLengthResponse(status, mime, stream, len)
            else newChunkedResponse(status, mime, stream)
            listOf("Content-Range", "Accept-Ranges").forEach { h -> resp.header(h)?.let { r.addHeader(h, it) } }
            return cors(r)
        }

        /**
         * Якщо з'єднання з сайтом обірвалось посеред відео (Wi-Fi, перепідключення
         * SSH-тунелю), докачуємо з того самого байта через Range. Chromecast цього не помічає.
         */
        private fun resumable(
            resp: okhttp3.Response, first: InputStream, len: Long,
            target: String, headers: Map<String, String>,
        ): InputStream {
            if (len <= 0) return first
            val start = if (resp.code == 206) {
                val m = contentRange.find(resp.header("Content-Range") ?: "") ?: return first
                m.groupValues[1].toLong()
            } else if (resp.code == 200) 0L else return first
            val end = start + len - 1
            return ResumingStream(first, start, end) { from ->
                val r = client().newCall(request(target, headers, "bytes=$from-$end")).execute()
                val ok = r.code == 206 &&
                    contentRange.find(r.header("Content-Range") ?: "")?.groupValues?.get(1)?.toLong() == from
                if (!ok) {
                    r.close()
                    throw IOException("server does not resume (${r.code})")
                }
                r.body!!.byteStream()
            }
        }

        private fun text(code: Int, msg: String): Response {
            val b = msg.toByteArray()
            return newFixedLengthResponse(Status(code, "Status"), NanoHTTPD.MIME_PLAINTEXT, ByteArrayInputStream(b), b.size.toLong())
        }

        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "*")
            r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
            return r
        }
    }
}
