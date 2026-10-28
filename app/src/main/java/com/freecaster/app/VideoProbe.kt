package com.freecaster.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds a video's duration and quality without downloading the file:
 * HLS: sum of #EXTINF and RESOLUTION from the master playlist, DASH: mediaPresentationDuration
 * and Representation height, MP4/MOV: the mvhd and tkhd headers.
 * Requests use the same client as the proxy, i.e. through the SSH tunnel when it is on.
 */
object VideoProbe {
    /** Live stream: no duration. */
    const val LIVE = -1.0

    /** [height]: picture height in pixels; [adaptive]: HLS/DASH that switches quality on its own. */
    data class Result(val duration: Double? = null, val height: Int? = null, val adaptive: Boolean = false)

    private val pool = Executors.newFixedThreadPool(3)
    private val extinf = Regex("#EXTINF:\\s*([\\d.]+)")
    private val mpdDuration = Regex("mediaPresentationDuration=\"([^\"]+)\"")
    private val isoDuration = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?")
    private val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)")
    private val mpdHeight = Regex("\\sheight=\"(\\d+)\"")

    /** Heights of HLS variants seen in master playlists, so each variant can be labeled too. */
    private val variantHeights = ConcurrentHashMap<String, Int>()

    /** Height of an HLS variant if its master playlist was already probed. */
    fun knownHeight(url: String): Int? = variantHeights[url]

    /** [done] is called on a background thread. */
    fun probe(v: DetectedVideo, done: (Result) -> Unit) {
        pool.execute {
            val r = try {
                when (v.kind) {
                    VideoKind.HLS -> hls(v.url, v.headers)
                    VideoKind.DASH -> dash(v.url, v.headers)
                    VideoKind.MP4, VideoKind.MOV, VideoKind.OTHER -> mp4(v.url, v.headers)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            done(r ?: Result())
        }
    }

    private fun text(url: String, headers: Map<String, String>): String? {
        StreamProxy.client().newCall(StreamProxy.request(url, headers, null)).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            if ((body.contentLength()) > 4_000_000) return null
            return body.string()
        }
    }

    private fun hls(url: String, headers: Map<String, String>): Result? {
        val txt = text(url, headers) ?: return null
        if (!txt.trimStart().startsWith("#EXTM3U")) return null
        if (!txt.contains("#EXT-X-STREAM-INF")) {
            return Result(mediaDuration(txt), variantHeights[url], adaptive = false)
        }
        // Master playlist: remember every variant's height; all variants have the same duration
        var first: String? = null
        var maxHeight = 0
        var pending: Int? = null
        for (raw in txt.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pending = resolution.find(line)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    val abs = url.toHttpUrlOrNull()?.resolve(line)?.toString()
                    if (abs != null) {
                        if (first == null) first = abs
                        if (pending > 0) variantHeights[abs] = pending
                    }
                    maxHeight = maxOf(maxHeight, pending)
                    pending = null
                }
            }
        }
        val duration = first?.let { text(it, headers) }?.let { mediaDuration(it) }
        return Result(duration, maxHeight.takeIf { it > 0 }, adaptive = true)
    }

    private fun mediaDuration(txt: String): Double? {
        if (!txt.contains("#EXT-X-ENDLIST") && !txt.contains("#EXT-X-PLAYLIST-TYPE:VOD")) return LIVE
        return extinf.findAll(txt).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }.takeIf { it > 0 }
    }

    private fun dash(url: String, headers: Map<String, String>): Result? {
        val txt = text(url, headers) ?: return null
        val height = mpdHeight.findAll(txt).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        if (txt.contains("type=\"dynamic\"")) return Result(LIVE, height, adaptive = true)
        val duration = mpdDuration.find(txt)?.groupValues?.get(1)?.let { iso ->
            isoDuration.matchEntire(iso)?.destructured?.let { (d, h, min, s) ->
                (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
                    (min.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
            }
        }
        return Result(duration, height, adaptive = true)
    }

    /** Walk the top-level boxes (ftyp, mdat, moov…) and read mvhd and tkhd inside moov. */
    private fun mp4(url: String, headers: Map<String, String>): Result? {
        var offset = 0L
        repeat(10) { i ->
            val head = range(url, headers, offset, 16) ?: return null
            if (head.size < 8) return null
            val type = String(head, 4, 4, Charsets.US_ASCII)
            if (i == 0 && type != "ftyp") return null // not an MP4
            var size = u32(head, 0)
            var hdr = 8
            if (size == 1L) {
                if (head.size < 16) return null
                size = u64(head, 8)
                hdr = 16
            }
            if (type == "moov") {
                // Enough for mvhd and the track headers, which come first in moov
                val moov = range(url, headers, offset + hdr, minOf(size - hdr, 16_384L).toInt()) ?: return null
                return Result(mvhd(moov), tkhdHeight(moov))
            }
            if (size < 8) return null // 0 = until end of file, so no moov after it
            offset += size
        }
        return null
    }

    private fun mvhd(b: ByteArray): Double? {
        var p = 0
        while (p + 8 <= b.size) {
            val size = u32(b, p).toInt()
            val type = String(b, p + 4, 4, Charsets.US_ASCII)
            if (type == "mvhd") {
                val v1 = b[p + 8].toInt() == 1
                val (scale, dur) = if (v1) {
                    if (p + 40 > b.size) return null
                    u32(b, p + 28) to u64(b, p + 32)
                } else {
                    if (p + 28 > b.size) return null
                    u32(b, p + 20) to u32(b, p + 24)
                }
                // 0 happens in fragmented MP4: duration unknown
                return if (scale > 0 && dur > 0) dur.toDouble() / scale else null
            }
            if (size < 8) return null
            p += size
        }
        return null
    }

    /**
     * Height of the video track: the largest height among the tkhd boxes (audio tracks have 0).
     * Searched by name because tkhd sits inside trak boxes; height is 16.16 fixed point.
     */
    private fun tkhdHeight(b: ByteArray): Int? {
        var best = 0
        var i = 4
        while (i + 4 <= b.size) {
            if (b[i] == 't'.code.toByte() && b[i + 1] == 'k'.code.toByte() &&
                b[i + 2] == 'h'.code.toByte() && b[i + 3] == 'd'.code.toByte()
            ) {
                val payload = i + 4
                val v1 = b[payload].toInt() == 1
                val hOff = payload + if (v1) 92 else 80
                if (hOff + 4 <= b.size) {
                    val h = (u32(b, hOff) shr 16).toInt()
                    if (h in 1..8_640) best = maxOf(best, h)
                }
            }
            i++
        }
        return best.takeIf { it > 0 }
    }

    /** Bytes [from]..[from+len); null if the server ignores Range beyond the start of the file. */
    private fun range(url: String, headers: Map<String, String>, from: Long, len: Int): ByteArray? {
        val req = StreamProxy.request(url, headers, "bytes=$from-${from + len - 1}")
        StreamProxy.client().newCall(req).execute().use { r ->
            if (r.code != 206 && !(r.code == 200 && from == 0L)) return null
            val input = r.body?.byteStream() ?: return null
            val out = ByteArray(len)
            var n = 0
            while (n < len) {
                val k = input.read(out, n, len - n)
                if (k < 0) break
                n += k
            }
            return out.copyOf(n)
        }
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
}
