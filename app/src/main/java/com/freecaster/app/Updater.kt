package com.freecaster.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Оновлення з GitHub Releases: кожна збірка в main публікується як реліз "build-N",
 * а N збігається з versionCode. Завжди напряму, незалежно від SSH-тунелю.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/maretes/FreeCasterAndroid/releases/latest"

    data class Release(val build: Long, val apkUrl: String, val size: Long, val notes: String)

    fun currentBuild(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Блокуючий виклик, не з головного потоку. */
    fun fetchLatest(): Release {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        val json = StreamProxy.direct.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub: HTTP ${r.code}")
            JSONObject(r.body?.string() ?: throw IOException("порожня відповідь"))
        }
        val build = json.optString("tag_name").removePrefix("build-").toLongOrNull()
            ?: throw IOException("невідомий реліз ${json.optString("tag_name")}")
        val assets = json.optJSONArray("assets") ?: throw IOException("у релізі немає APK")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(build, a.getString("browser_download_url"), a.optLong("size"), json.optString("body").trim())
            }
        }
        throw IOException("у релізі немає APK")
    }

    /**
     * Качає APK одразу в сесію PackageInstaller (без тимчасового файлу) і запускає встановлення.
     * Система покаже одне підтвердження, далі додаток перезапуститься новою версією.
     */
    fun install(ctx: Context, rel: Release, progress: (Int) -> Unit) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                val req = Request.Builder().url(rel.apkUrl).build()
                StreamProxy.direct.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("порожня відповідь")
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                    session.openWrite("base.apk", 0, if (total > 0) total else -1).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = if (total > 0) (done * 100 / total).toInt() else 0
                                if (p != last) {
                                    last = p
                                    progress(p)
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
                session.commit(sender.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }
}

/** Результат встановлення від PackageInstaller. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // додаток уже перезапускається
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(
                    context,
                    "Оновлення підписане іншим ключем. Один раз видаліть FreeCaster і встановіть APK вручну",
                    Toast.LENGTH_LONG,
                ).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // користувач натиснув "Скасувати"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "невідома помилка"
                Toast.makeText(context, "Оновлення не встановилось: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
