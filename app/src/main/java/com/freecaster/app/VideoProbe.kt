package com.freecaster.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.Executors

/**
 * Визначає тривалість відео без завантаження файлу:
 * HLS: сума #EXTINF, DASH: mediaPresentationDuration, MP4/MOV: заголовок mvhd.
 * Запити йдуть тим самим клієнтом, що й проксі, тобто через SSH-тунель, якщо він увімкнений.
 */
object VideoProbe {
    /** Пряма трансляція: тривалості немає. */
    const val LIVE = -1.0

    private val pool = Executors.newFixedThreadPool(3)
    private val extinf = Regex("#EXTINF:\\s*([\\d.]+)")
    private val mpdDuration = Regex("mediaPresentationDuration=\"([^\"]+)\"")
    private val isoDuration = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?")

    /** [done] викликається з фонового потоку; null, якщо визначити не вдалося. */
    fun probe(v: DetectedVideo, done: (Double?) -> Unit) {
        pool.execute {
            val d = try {
                when (v.kind) {
                    VideoKind.HLS -> hls(v.url, v.headers, depth = 0)
                    VideoKind.DASH -> dash(v.url, v.headers)
                    VideoKind.MP4, VideoKind.MOV, VideoKind.OTHER -> mp4(v.url, v.headers)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            done(d)
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

    private fun hls(url: String, headers: Map<String, String>, depth: Int): Double? {
        val txt = text(url, headers) ?: return null
        if (!txt.trimStart().startsWith("#EXTM3U")) return null
        if (txt.contains("#EXT-X-STREAM-INF")) {
            // Головний плейлист: тривалість однакова в усіх якостях, беремо першу
            if (depth > 0) return null
            val variant = txt.lineSequence().map { it.trim() }
                .dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }.drop(1)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
            val abs = url.toHttpUrlOrNull()?.resolve(variant)?.toString() ?: return null
            return hls(abs, headers, depth + 1)
        }
        if (!txt.contains("#EXT-X-ENDLIST") && !txt.contains("#EXT-X-PLAYLIST-TYPE:VOD")) return LIVE
        return extinf.findAll(txt).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }.takeIf { it > 0 }
    }

    private fun dash(url: String, headers: Map<String, String>): Double? {
        val txt = text(url, headers) ?: return null
        if (txt.contains("type=\"dynamic\"")) return LIVE
        val iso = mpdDuration.find(txt)?.groupValues?.get(1) ?: return null
        val m = isoDuration.matchEntire(iso) ?: return null
        val (d, h, min, s) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
            (min.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
    }

    /** Йдемо по коробках верхнього рівня (ftyp, mdat, moov…) і читаємо mvhd усередині moov. */
    private fun mp4(url: String, headers: Map<String, String>): Double? {
        var offset = 0L
        repeat(10) { i ->
            val head = range(url, headers, offset, 16) ?: return null
            if (head.size < 8) return null
            val type = String(head, 4, 4, Charsets.US_ASCII)
            if (i == 0 && type != "ftyp") return null // не MP4
            var size = u32(head, 0)
            var hdr = 8
            if (size == 1L) {
                if (head.size < 16) return null
                size = u64(head, 8)
                hdr = 16
            }
            if (type == "moov") {
                val moov = range(url, headers, offset + hdr, 512) ?: return null
                return mvhd(moov)
            }
            if (size < 8) return null // 0 = до кінця файлу, moov там уже не буде
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
                // 0 буває у фрагментованих MP4: тривалість невідома
                return if (scale > 0 && dur > 0) dur.toDouble() / scale else null
            }
            if (size < 8) return null
            p += size
        }
        return null
    }

    /** Байти [from]..[from+len); null, якщо сервер не підтримує Range далі початку файлу. */
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
