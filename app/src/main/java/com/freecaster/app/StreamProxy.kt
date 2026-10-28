package com.freecaster.app

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
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

            val req = Request.Builder().url(target)
            headers.forEach { (k, v) -> req.header(k, v) }
            session.headers["range"]?.let { req.header("Range", it) }

            val resp = try {
                http.newCall(req.build()).execute()
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
            val r = if (len >= 0) newFixedLengthResponse(status, mime, body.byteStream(), len)
            else newChunkedResponse(status, mime, body.byteStream())
            listOf("Content-Range", "Accept-Ranges").forEach { h -> resp.header(h)?.let { r.addHeader(h, it) } }
            return cors(r)
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
