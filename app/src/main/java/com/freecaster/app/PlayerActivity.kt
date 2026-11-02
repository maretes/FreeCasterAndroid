package com.freecaster.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Request

/** Built-in phone player: same site headers and tunnel as the browser, so videos open like on the page. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = PlayerView(this)
        setContentView(view)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player != null) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String> ?: hashMapOf()
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { VideoKind.valueOf(it) }.getOrNull() }

        // Picks the tunnel per request, so it follows the tunnel being switched on or off
        val calls = object : Call.Factory {
            override fun newCall(request: Request): Call = StreamProxy.client().newCall(request)
        }
        val ds = OkHttpDataSource.Factory(calls).setDefaultRequestProperties(headers)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
            }
        })
        val mime = when (kind) {
            VideoKind.HLS -> MimeTypes.APPLICATION_M3U8
            VideoKind.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
        p.seekTo(position)
        p.playWhenReady = true
        p.prepare()
        view.player = p
        player = p
    }

    override fun onStop() {
        super.onStop()
        player?.let { position = it.currentPosition; it.release() }
        player = null
        view.player = null
    }

    private var position = 0L

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_HEADERS = "headers"

        fun start(ctx: Context, v: DetectedVideo) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, v.url)
                    .putExtra(EXTRA_KIND, v.kind.name)
                    .putExtra(EXTRA_HEADERS, HashMap(v.headers))
            )
        }
    }
}
