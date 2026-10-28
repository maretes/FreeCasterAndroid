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
 * "Звук на телефоні": відео грає на Chromecast без звуку, а телефон програє ту саму
 * доріжку (у навушники, Bluetooth-колонку, Google Home у режимі Bluetooth…)
 * і підлаштовується під позицію Chromecast.
 *
 * Синхронізація: якщо телефон відстав або TV перемотали, перескакуємо трохи вперед
 * і чекаємо на паузі, поки TV наздожене; дрібну різницю вирівнюємо швидкістю ±5%.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object AudioRelay {
    private const val TICK_MS = 250L
    private const val SEEK_LEAD_MS = 1_500L // запас на буферизацію після перемотування
    private const val PREFS = "audio"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var client: RemoteMediaClient? = null
    private var speed = 1f

    private var lastCast: DetectedVideo? = null
    private var lastViaPhone = false

    /** Компенсація затримки виводу (Bluetooth ~150–300 мс): телефон грає на стільки ж попереду. */
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

    /** Chromecast почав нове відео: якщо звук був на телефоні, переводимо на нове відео. */
    fun onCastStarted(ctx: Context, v: DetectedVideo, viaPhone: Boolean) {
        lastCast = v
        lastViaPhone = viaPhone
        if (isActive) {
            stop(ctx, unmute = false)
            start(ctx)?.let { toast(ctx, it) }
        }
    }

    /** null, якщо все добре, інакше текст помилки. */
    fun start(ctx: Context): String? {
        if (isActive) return null
        val v = lastCast ?: return "Спочатку запустіть відео на Chromecast"
        val rmc = remoteClient(ctx) ?: return "Немає підключення до Chromecast"
        if (rmc.mediaStatus == null) return "Спочатку запустіть відео на Chromecast"
        if (v.duration == VideoProbe.LIVE || rmc.isLiveStream) {
            return "Для прямих трансляцій звук на телефоні поки не підтримується"
        }
        val appCtx = ctx.applicationContext
        app = appCtx
        delayMs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("delay_ms", 200)

        // Той самий шлях, що й у проксі: заголовки сайту + SSH-тунель, якщо він увімкнений
        val calls = object : Call.Factory {
            override fun newCall(request: Request): Call = StreamProxy.client().newCall(request)
        }
        val ds = OkHttpDataSource.Factory(calls).setDefaultRequestProperties(v.headers)
        val p = ExoPlayer.Builder(appCtx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true) // лише звук
            .build()
        // Без аудіофокусу: інакше телефон сам ставив би паузу, а TV грав далі
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            false,
        )
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(appCtx, "Телефон не зміг відтворити звук: ${error.errorCodeName}. Звук знову на Chromecast")
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
        ProxyService.start(appCtx) // щоб звук не зупинявся при вимкненому екрані
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
        // Сервіс потрібен і самій трансляції через телефон, тоді його не чіпаємо
        if (!lastViaPhone) ProxyService.stop(ctx.applicationContext)
    }

    private fun sync() {
        val p = player ?: return
        val rmc = client ?: return
        val ctx = app ?: return
        val connected = remoteClient(ctx) === rmc
        if (!connected) { // трансляцію зупинили
            stop(ctx, unmute = false)
            return
        }
        val target = rmc.approximateStreamPosition + delayMs
        val drift = p.currentPosition - target

        if (rmc.playerState != MediaStatus.PLAYER_STATE_PLAYING) {
            // TV на паузі чи буферизується: чекаємо, але тримаємо позицію поруч
            p.playWhenReady = false
            if (abs(drift) > 1_000 && rmc.playerState == MediaStatus.PLAYER_STATE_PAUSED) p.seekTo(target.coerceAtLeast(0))
            return
        }
        if (p.playbackState != Player.STATE_READY) return // телефон ще буферизується

        when {
            // Сильно відстали або TV перемотали: стрибаємо вперед із запасом і чекаємо TV
            drift < -1_500 || drift > 5_000 -> {
                p.seekTo((target + SEEK_LEAD_MS).coerceAtLeast(0))
                p.playWhenReady = false
                setSpeed(p, 1f)
            }
            drift > 300 -> p.playWhenReady = false // попереду: чекаємо, поки TV наздожене
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
            toast(activity, "Звук знову на Chromecast")
        } else {
            val err = start(activity)
            toast(activity, err ?: "🎧 Звук на телефоні. Якщо не збігається з губами, підлаштуйте затримку в меню")
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
            label.text = "Звук на телефоні раніше на ${v.toInt()} мс\n(Bluetooth зазвичай 150–300 мс)"
        }
        show(slider.value)
        // Змінюємо одразу, щоб підганяти "на слух" під час перегляду
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
            .setTitle("Затримка звуку")
            .setView(box)
            .setPositiveButton("Готово", null)
            .show()
    }

    /**
     * Поки звук на телефоні, кнопки гучності керують телефоном, а не (заглушеним) Chromecast.
     * Працює, коли FreeCaster на екрані.
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
