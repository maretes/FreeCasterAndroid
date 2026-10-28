package com.freecaster.app

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

    /**
     * Blocking. Calls [onFound] for every new renderer during [timeoutMs].
     * Returns what every device that answered is (IP → name / reason it isn't a TV), for diagnostics.
     * An empty map means the network blocks discovery (client isolation, guest Wi-Fi).
     */
    fun discover(ctx: Context, timeoutMs: Int = 6_000, onFound: (DlnaDevice) -> Unit): Map<String, String> {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        // Some phones drop SSDP traffic unless a multicast lock is held
        val lock = wifi?.createMulticastLock("FreeCaster:ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val seen = HashSet<String>()
        val report = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pending = ArrayList<Thread>()
        try {
            openSocket(ctx).use { sock ->
                sock.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                // Some TVs only answer generic searches, so ask for everything and filter by description
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1", AVT, "upnp:rootdevice", "ssdp:all",
                )
                fun search() {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\n\r\n"
                        val data = msg.toByteArray(Charsets.US_ASCII)
                        runCatching { sock.send(DatagramPacket(data, data.size, group, 1900)) }
                    }
                }
                search()
                var resent = false
                val start = System.currentTimeMillis()
                val deadline = start + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    // UDP gets lost on busy Wi-Fi: ask once more halfway
                    if (!resent && System.currentTimeMillis() - start > timeoutMs / 3) {
                        resent = true
                        search()
                    }
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val ip = pkt.address?.hostAddress ?: continue
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    fun header(name: String) = text.lineSequence()
                        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    report.putIfAbsent(ip, header("SERVER") ?: "?")
                    val location = header("LOCATION") ?: continue
                    if (!seen.add(location)) continue
                    // Fetching the description takes a moment; don't hold up listening for others
                    pending += Thread {
                        try {
                            val d = describe(location)
                            if (d.device != null) {
                                report[ip] = d.summary
                                onFound(d.device)
                            } else if (report[ip]?.startsWith("✓") != true) {
                                report[ip] = d.summary
                            }
                        } catch (e: Exception) {
                            if (report[ip]?.startsWith("✓") != true) report[ip] = "${header("SERVER") ?: "?"}: ${e.message}"
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        } finally {
            lock?.release()
        }
        // Let descriptions that are still loading finish, so the report is complete
        val until = System.currentTimeMillis() + 3_000
        for (t in pending) t.join((until - System.currentTimeMillis()).coerceAtLeast(1))
        return HashMap(report)
    }

    /** Bound to the Wi-Fi address so the search never goes out over mobile data or a VPN. */
    private fun openSocket(ctx: Context): DatagramSocket {
        val ip = NetUtils.localIpv4(ctx) ?: return DatagramSocket()
        return try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName(ip), 0))
        } catch (e: Exception) {
            DatagramSocket()
        }
    }

    private class Description(val device: DlnaDevice?, val summary: String)

    /**
     * Reads the device description and finds the control URLs. Parsed with regexes rather than
     * a strict XML parser: some TVs send a BOM, leading spaces or odd namespaces that break it.
     */
    private fun describe(location: String): Description {
        val body = StreamProxy.direct.newCall(Request.Builder().url(location).build()).execute().use {
            if (!it.isSuccessful) throw IOException("description HTTP ${it.code}")
            it.body?.string() ?: throw IOException("empty description")
        }
        val name = tagText(body, "friendlyName").orEmpty()
        val model = listOfNotNull(tagText(body, "manufacturer"), tagText(body, "modelName"))
            .filter { it.isNotEmpty() }.joinToString(" ")
        val base = tagText(body, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        var avt: String? = null
        var rc: String? = null
        for (m in Regex("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
            val block = m.groupValues[1]
            val type = tagText(block, "serviceType") ?: continue
            val control = tagText(block, "controlURL") ?: continue
            when {
                type.contains(":service:AVTransport:") -> avt = control
                type.contains(":service:RenderingControl:") -> rc = control
            }
        }
        val label = listOf(name, model).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "?" }
        val control = avt ?: return Description(null, "$label: no AVTransport (not a video renderer)")
        val host = URL(location).host
        val device = DlnaDevice(
            name = name.ifEmpty { host },
            model = model,
            host = host,
            avTransport = URL(URL(base), control).toString(),
            rendering = rc?.let { URL(URL(base), it).toString() },
        )
        return Description(device, "✓ $label")
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
