package com.freecaster.app

import android.net.Uri

/** Type of a detected video + the MIME type we pass to the Chromecast. */
enum class VideoKind(val label: String, val mime: String) {
    HLS("HLS", "application/x-mpegurl"),
    DASH("DASH", "application/dash+xml"),
    MP4("MP4", "video/mp4"),
    WEBM("WebM", "video/webm"),
    MKV("MKV", "video/x-matroska"),
    MOV("MOV", "video/quicktime"),
    OTHER("Video", "video/mp4"),
}

data class DetectedVideo(
    val url: String,
    val kind: VideoKind,
    /** Headers the browser used to request the video (Referer, Cookie, User-Agent...). */
    val headers: Map<String, String>,
    /** Seconds; [VideoProbe.LIVE] for live streams; null while unknown. */
    val duration: Double? = null,
) {
    /** Shorter than a minute: most likely an ad or a promo. */
    val isShort: Boolean get() = duration != null && duration >= 0 && duration < 60
}

object VideoDetector {
    /** Tells from the URL whether it is a video. Segments (.ts / .m4s) are ignored. */
    fun classify(url: String): VideoKind? {
        if (!url.startsWith("http")) return null
        val lower = url.lowercase()
        val path = try {
            Uri.parse(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        return when {
            path.endsWith(".m3u8") || lower.contains(".m3u8") -> VideoKind.HLS
            path.endsWith(".mpd") -> VideoKind.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") -> VideoKind.MP4
            path.endsWith(".webm") -> VideoKind.WEBM
            path.endsWith(".mkv") -> VideoKind.MKV
            path.endsWith(".mov") -> VideoKind.MOV
            else -> null
        }
    }

    /** JS that looks for <video> on the page (in all frames) and reports to the app. */
    const val SCAN_JS = """
(function(){
  if (window.__freeCaster) return; window.__freeCaster = 1;
  function report(s, d){
    if (s && s.indexOf('http') === 0) { try { CasterBridge.onVideo(s, location.href, d); } catch(e){} }
  }
  // Duration: seconds, -1 live stream, -2 not known yet
  function dur(v){
    var d = v && v.duration;
    if (d === Infinity) return -1;
    return (typeof d === 'number' && isFinite(d) && d > 0) ? d : -2;
  }
  function scan(){
    try {
      document.querySelectorAll('video, audio').forEach(function(v){
        var d = dur(v);
        report(v.currentSrc || v.src, d);
        v.querySelectorAll('source').forEach(function(s){ report(s.src, d); });
      });
    } catch(e){}
  }
  setInterval(scan, 2000);
  document.addEventListener('play', scan, true);
  document.addEventListener('loadedmetadata', scan, true);
})();
"""
}
