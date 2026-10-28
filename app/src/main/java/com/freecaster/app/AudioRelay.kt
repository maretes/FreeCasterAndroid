package com.freecaster.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import okhttp3.Call
import okhttp3.Request
import kotlin.math.abs

/**
 * "Audio on phone": the video plays muted on the Chromecast while the phone plays the
 * same track (headphones, a Bluetooth speaker, Google Home in Bluetooth mode…)
 * and follows the Chromecast's position.
 *
 * Sync: if the phone falls behind or the TV was seeked, jump slightly ahead and wait
 * paused until the TV catches up; small drift is corrected with ±5% playback speed.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object AudioRelay {
    private const val TICK_MS = 250L
    private const val SEEK_LEAD_MS = 1_500L // buffering headroom after a seek
    private const val PREFS = "audio"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var client: RemoteMediaClient? = null
    private var speed = 1f

    private var lastCast: DetectedVideo? = null
    private var lastViaPhone = false

    /** Output latency compensation (Bluetooth ~150–300 ms): the phone plays this much ahead. */
    @Volatile var delayMs = 200L
        private set

    val isActive: Boolean get() = player != null

    private val tick = object : Runnable {
        override fun run() {
            sync()
            if (player != null) main.postDelayed(this, TICK_MS)
        }
    }

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = sync()
    }

    /** The Chromecast started a new video: if audio was on the phone, follow the new video. */
    fun onCastStarted(ctx: Context, v: DetectedVideo, viaPhone: Boolean) {
        lastCast = v
        lastViaPhone = viaPhone
        if (isActive) {
            stop(ctx, unmute = false)
            start(ctx)?.let { toast(ctx, ctx.getString(it)) }
        }
    }

    /** null on success, otherwise an error string resource. */
    @StringRes
    fun start(ctx: Context): Int? {
        if (isActive) return null
        val v = lastCast ?: return R.string.audio_cast_first
        val rmc = remoteClient(ctx) ?: return R.string.audio_no_cast
        if (rmc.mediaStatus == null) return R.string.audio_cast_first
        if (v.duration == VideoProbe.LIVE || rmc.isLiveStream) return R.string.audio_live
        val appCtx = ctx.applicationContext
        app = appCtx
        delayMs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("delay_ms", 200)

        // Same path as the proxy: site headers + the SSH tunnel when it is on
        val calls = object : Call.Factory {
            override fun newCall(request: Request): Call = StreamProxy.client().newCall(request)
        }
        val ds = OkHttpDataSource.Factory(calls).setDefaultRequestProperties(v.headers)
        val p = ExoPlayer.Builder(appCtx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true) // audio only
            .build()
        // No audio focus: otherwise the phone would pause on its own while the TV keeps playing
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            false,
        )
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(appCtx, appCtx.getString(R.string.audio_failed, error.errorCodeName))
                stop(appCtx)
            }
        })
        val mime = when (v.kind) {
            VideoKind.HLS -> MimeTypes.APPLICATION_M3U8
            VideoKind.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        p.setMediaItem(MediaItem.Builder().setUri(v.url).setMimeType(mime).build())
        p.prepare()
        p.seekTo((rmc.approximateStreamPosition + delayMs + SEEK_LEAD_MS).coerceAtLeast(0))
        p.playWhenReady = false

        player = p
        client = rmc
        speed = 1f
        rmc.registerCallback(remoteCallback)
        rmc.setStreamMute(true)
        ProxyService.start(appCtx) // so the audio doesn't stop with the screen off
        main.post(tick)
        return null
    }

    fun stop(ctx: Context, unmute: Boolean = true) {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        client?.let {
            it.unregisterCallback(remoteCallback)
            if (unmute) it.setStreamMute(false)
        }
        client = null
        // Casting via phone needs the service too; leave it running then
        if (!lastViaPhone) ProxyService.stop(ctx.applicationContext)
    }

    private fun sync() {
        val p = player ?: return
        val rmc = client ?: return
        val ctx = app ?: return
        val connected = remoteClient(ctx) === rmc
        if (!connected) { // casting was stopped
            stop(ctx, unmute = false)
            return
        }
        val target = rmc.approximateStreamPosition + delayMs
        val drift = p.currentPosition - target

        if (rmc.playerState != MediaStatus.PLAYER_STATE_PLAYING) {
            // TV paused or buffering: wait, but keep the position close
            p.playWhenReady = false
            if (abs(drift) > 1_000 && rmc.playerState == MediaStatus.PLAYER_STATE_PAUSED) p.seekTo(target.coerceAtLeast(0))
            return
        }
        if (p.playbackState != Player.STATE_READY) return // the phone is still buffering

        when {
            // Far behind or the TV was seeked: jump ahead with headroom and wait for the TV
            drift < -1_500 || drift > 5_000 -> {
                p.seekTo((target + SEEK_LEAD_MS).coerceAtLeast(0))
                p.playWhenReady = false
                setSpeed(p, 1f)
            }
            drift > 300 -> p.playWhenReady = false // ahead: wait for the TV to catch up
            else -> {
                p.playWhenReady = true
                setSpeed(p, if (abs(drift) < 30) 1f else (1f - drift / 2_000f).coerceIn(0.95f, 1.05f))
            }
        }
    }

    private fun setSpeed(p: ExoPlayer, s: Float) {
        if (abs(s - speed) < 0.005f) return
        speed = s
        p.playbackParameters = PlaybackParameters(s)
    }

    private fun remoteClient(ctx: Context): RemoteMediaClient? = try {
        CastContext.getSharedInstance(ctx).sessionManager.currentCastSession
            ?.takeIf { it.isConnected }?.remoteMediaClient
    } catch (e: Exception) {
        null
    }

    // ---------------- UI ----------------

    fun toggle(activity: Activity) {
        if (isActive) {
            stop(activity)
            toast(activity, activity.getString(R.string.audio_back_tv))
        } else {
            val err = start(activity)
            toast(activity, activity.getString(err ?: R.string.audio_on_phone))
        }
        activity.invalidateOptionsMenu()
    }

    fun showDelayDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val label = TextView(activity).apply { textSize = 16f }
        val slider = Slider(activity).apply {
            valueFrom = 0f
            valueTo = 1_000f
            stepSize = 10f
            value = prefs.getLong("delay_ms", 200).coerceIn(0, 1_000).toFloat()
        }
        fun show(v: Float) {
            label.text = activity.getString(R.string.audio_delay_label, v.toInt())
        }
        show(slider.value)
        // Apply immediately so it can be tuned by ear while watching
        slider.addOnChangeListener { _, v, _ ->
            show(v)
            delayMs = v.toLong()
            prefs.edit().putLong("delay_ms", v.toLong()).apply()
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(label)
            addView(slider)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audio_delay)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /**
     * While audio is on the phone, the volume keys control the phone, not the (muted) Chromecast.
     * Works while FreeCaster is on screen.
     */
    fun handleVolumeKey(ctx: Context, event: KeyEvent): Boolean {
        if (!isActive) return false
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            ctx.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
}
