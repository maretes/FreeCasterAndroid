package com.freecaster.app

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Simple domain-based ad blocker. Add your own domains to the list. */
object AdBlocker {
    private val hosts = hashSetOf(
        // Google ads
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "imasdk.googleapis.com", "googletagservices.com",
        // Pop-ups and aggressive networks
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net",
        "onclkds.com", "adsterra.com", "adsterratech.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.net", "trafficjunky.com", "trafficstars.com", "tsyndicate.com",
        "hilltopads.net", "clickadu.com", "clickaine.com", "adcash.com", "admaven.com",
        "ad-maven.com", "monetag.com", "a-ads.com", "richpush.co", "pushame.com",
        "realsrv.com", "mgid.com", "taboola.com", "outbrain.com", "revcontent.com",
        "zeropark.com", "popmyads.com", "galaksion.com", "bidgear.com", "adspyglass.com",
        "rollerads.com", "evadav.com", "pushground.com", "dolohen.com", "acint.net",
        // Large ad exchanges
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "casalemedia.com", "smartadserver.com", "advertising.com", "adform.net",
        "yieldmo.com", "33across.com", "sharethrough.com", "media.net", "amazon-adsystem.com",
        "moatads.com", "adsafeprotected.com", "serving-sys.com", "adroll.com", "zedo.com",
        // Trackers
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "mc.yandex.ru", "an.yandex.ru",
        "adfox.ru", "yandexadexchange.net",
    )

    fun isAd(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase()
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
