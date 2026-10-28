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
 * Updates from GitHub Releases: every build on main is published as release "build-N",
 * where N equals versionCode. Always direct, regardless of the SSH tunnel.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/maretes/FreeCasterAndroid/releases/latest"

    data class Release(val build: Long, val apkUrl: String, val size: Long, val notes: String)

    fun currentBuild(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Blocking call, not on the main thread. */
    fun fetchLatest(): Release {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        val json = StreamProxy.direct.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub: HTTP ${r.code}")
            JSONObject(r.body?.string() ?: throw IOException("empty response"))
        }
        val build = json.optString("tag_name").removePrefix("build-").toLongOrNull()
            ?: throw IOException("unknown release ${json.optString("tag_name")}")
        val assets = json.optJSONArray("assets") ?: throw IOException("no APK in the release")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(build, a.getString("browser_download_url"), a.optLong("size"), json.optString("body").trim())
            }
        }
        throw IOException("no APK in the release")
    }

    /**
     * Streams the APK straight into a PackageInstaller session (no temp file) and starts the install.
     * The system shows one confirmation, then the app restarts as the new version.
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
                    val body = r.body ?: throw IOException("empty response")
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

/** Install result from PackageInstaller. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is already restarting
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(context, context.getString(R.string.update_wrong_key), Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user tapped "Cancel"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: context.getString(R.string.unknown_error)
                Toast.makeText(context, context.getString(R.string.update_not_installed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
