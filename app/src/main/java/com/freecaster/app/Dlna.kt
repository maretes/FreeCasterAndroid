package com.freecaster.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** A TV or player found on the LAN that accepts video by URL (UPnP MediaRenderer). */
data class DlnaDevice(
    val name: String,
    val model: String,
    val host: String,
    val avTransport: String,
    val rendering: String?,
)

/**
 * Minimal DLNA/UPnP client: SSDP discovery plus the AVTransport and RenderingControl
 * SOAP actions needed to play, pause, seek and change volume. Works with most smart TVs
 * (Samsung, LG, Sony, Philips, Hisense…), Kodi and other media players.
 */
object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private val xml = "text/xml; charset=\"utf-8\"".toMediaType()

    /** Blocking. Calls [onFound] for every new renderer during [timeoutMs]. */
    fun discover(ctx: Context, timeoutMs: Int = 4_000, onFound: (DlnaDevice) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        // Some phones drop SSDP traffic unless a multicast lock is held
        val lock = wifi?.createMulticastLock("FreeCaster:ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val seen = HashSet<String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AVT)) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val data = msg.toByteArray(Charsets.US_ASCII)
                    repeat(2) { sock.send(DatagramPacket(data, data.size, group, 1900)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    if (!seen.add(location)) continue
                    // Fetching the description takes a moment; don't hold up listening for others
                    Thread {
                        runCatching { describe(location) }.getOrNull()?.let(onFound)
                    }.apply { isDaemon = true }.start()
                }
            }
        } finally {
            lock?.release()
        }
    }

    /** Reads the device description and finds the control URLs. */
    private fun describe(location: String): DlnaDevice? {
        val body = StreamProxy.direct.newCall(Request.Builder().url(location).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string() ?: return null
        }
        val p = Xml.newPullParser()
        p.setInput(body.reader())
        var name = ""
        var manufacturer = ""
        var model = ""
        var base = location
        var serviceType = ""
        var avt: String? = null
        var rc: String? = null
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> tag = p.name
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isEmpty()) continue
                    when (tag) {
                        "friendlyName" -> if (name.isEmpty()) name = t
                        "manufacturer" -> if (manufacturer.isEmpty()) manufacturer = t
                        "modelName" -> if (model.isEmpty()) model = t
                        "URLBase" -> base = t
                        "serviceType" -> serviceType = t
                        "controlURL" -> when {
                            serviceType.startsWith("urn:schemas-upnp-org:service:AVTransport") -> avt = t
                            serviceType.startsWith("urn:schemas-upnp-org:service:RenderingControl") -> rc = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> tag = ""
            }
        }
        val control = avt ?: return null
        return DlnaDevice(
            name = name.ifEmpty { URL(location).host },
            model = listOf(manufacturer, model).filter { it.isNotEmpty() }.joinToString(" "),
            host = URL(location).host,
            avTransport = URL(URL(base), control).toString(),
            rendering = rc?.let { URL(URL(base), it).toString() },
        )
    }

    // ---------------- Control ----------------

    /** Stops whatever is playing, loads [url] and starts it. Blocking. */
    fun play(d: DlnaDevice, url: String, title: String, mime: String) {
        runCatching { soap(d.avTransport, AVT, "Stop", "<InstanceID>0</InstanceID>") }
        val meta = didl(url, title, mime)
        soap(
            d.avTransport, AVT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>",
        )
        soap(d.avTransport, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun pause(d: DlnaDevice) = soap(d.avTransport, AVT, "Pause", "<InstanceID>0</InstanceID>")

    fun resume(d: DlnaDevice) = soap(d.avTransport, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stop(d: DlnaDevice) = soap(d.avTransport, AVT, "Stop", "<InstanceID>0</InstanceID>")

    fun seek(d: DlnaDevice, seconds: Long) = soap(
        d.avTransport, AVT, "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${hms(seconds.coerceAtLeast(0))}</Target>",
    )

    /** Position and duration in seconds (duration 0 if unknown). */
    fun position(d: DlnaDevice): Pair<Long, Long> {
        val r = soap(d.avTransport, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        return seconds(tagText(r, "RelTime")) to seconds(tagText(r, "TrackDuration"))
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT… */
    fun state(d: DlnaDevice): String =
        tagText(soap(d.avTransport, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState") ?: ""

    fun volume(d: DlnaDevice): Int? {
        val rc = d.rendering ?: return null
        return tagText(soap(rc, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")
            ?.toIntOrNull()
    }

    fun setVolume(d: DlnaDevice, v: Int) {
        val rc = d.rendering ?: return
        soap(rc, RC, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>")
    }

    private fun soap(controlUrl: String, service: String, action: String, args: String): String {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(controlUrl)
            .post(envelope.toRequestBody(xml))
            .header("SOAPACTION", "\"$service#$action\"")
            .build()
        // LAN traffic: always direct, never through the tunnel
        StreamProxy.direct.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = tagText(body, "errorDescription") ?: "HTTP ${r.code}"
                throw IOException("$action: $err")
            }
            return body
        }
    }

    /**
     * Item metadata. DLNA.ORG_OP=01 tells the TV it can seek with Range requests;
     * Samsung TVs refuse to play without proper protocolInfo.
     */
    private fun didl(url: String, title: String, mime: String) =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:${StreamProxy.DLNA_FEATURES}\">${esc(url)}</res>" +
            "</item></DIDL-Lite>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun tagText(xml: String, tag: String): String? =
        Regex("<(?:\\w+:)?$tag[^>]*>(.*?)</(?:\\w+:)?$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?.groupValues?.get(1)?.trim()

    private fun hms(s: Long) = String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)

    /** "1:02:03" or "1:02:03.500" → seconds; "NOT_IMPLEMENTED" → 0. */
    private fun seconds(t: String?): Long {
        val parts = t?.substringBefore('.')?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val (h, m, s) = parts.map { it.toLongOrNull() ?: return 0 }
        return h * 3600 + m * 60 + s
    }
}
