package com.freecaster.app

import android.net.Uri

/** Тип знайденого відео + MIME, який ми передаємо Chromecast. */
enum class VideoKind(val label: String, val mime: String) {
    HLS("HLS", "application/x-mpegurl"),
    DASH("DASH", "application/dash+xml"),
    MP4("MP4", "video/mp4"),
    WEBM("WebM", "video/webm"),
    MKV("MKV", "video/x-matroska"),
    MOV("MOV", "video/quicktime"),
    OTHER("Відео", "video/mp4"),
}

data class DetectedVideo(
    val url: String,
    val kind: VideoKind,
    /** Заголовки, з якими браузер запитував відео (Referer, Cookie, User-Agent...). */
    val headers: Map<String, String>,
)

object VideoDetector {
    /** Визначає за адресою, чи це відео. Сегменти (.ts / .m4s) ігноруємо. */
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

    /** JS, що шукає <video> на сторінці (у всіх фреймах) і повідомляє додатку. */
    const val SCAN_JS = """
(function(){
  if (window.__freeCaster) return; window.__freeCaster = 1;
  function report(s){
    if (s && s.indexOf('http') === 0) { try { CasterBridge.onVideo(s, location.href); } catch(e){} }
  }
  function scan(){
    try {
      document.querySelectorAll('video, video source, audio').forEach(function(v){
        report(v.currentSrc || v.src);
      });
    } catch(e){}
  }
  setInterval(scan, 2000);
  document.addEventListener('play', scan, true);
  document.addEventListener('loadedmetadata', scan, true);
})();
"""
}
