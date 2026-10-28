package com.freecaster.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshConfig(val host: String, val port: Int, val user: String, val password: String)

/** Дані SSH-сервера. Пароль шифрується ключем з Android Keystore. */
object SshSettings {
    private const val PREFS = "ssh"
    private const val KEY_ALIAS = "freecaster_ssh"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun knownHosts(ctx: Context) = File(ctx.filesDir, "known_hosts")

    fun load(ctx: Context): SshConfig? {
        val p = prefs(ctx)
        val host = p.getString("host", null) ?: return null
        val user = p.getString("user", null) ?: return null
        val pass = p.getString("pass", null)?.let { decrypt(it) } ?: return null
        return SshConfig(host, p.getInt("port", 22), user, pass)
    }

    fun save(ctx: Context, cfg: SshConfig) {
        val old = load(ctx)
        // Інший сервер: забуваємо ключ попереднього
        if (old == null || old.host != cfg.host || old.port != cfg.port) forgetHostKey(ctx)
        prefs(ctx).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
            .putString("pass", encrypt(cfg.password))
            .apply()
    }

    fun forgetHostKey(ctx: Context) {
        knownHosts(ctx).delete()
    }

    /** Чи був тунель увімкнений (щоб після перезапуску процесу kill switch не вимкнувся). */
    fun wasEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    // commit(), а не apply(): прапорець має бути на диску ще до того, як щось піде не так,
    // інакше після перезапуску тунель увімкнеться сам
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).commit()
    }

    /**
     * Приймає і просто "host", і "user@host:port", і цілу команду
     * на кшталт "ssh -D 1080 -N -C user@host -p 2222".
     */
    fun parse(hostIn: String, portIn: String, userIn: String, pass: String): SshConfig? {
        var host = hostIn.trim()
        var user = userIn.trim()
        var port = portIn.trim().toIntOrNull() ?: 22
        if (host.startsWith("ssh ")) {
            val tokens = host.split(Regex("\\s+"))
            val p = tokens.indexOf("-p")
            if (p >= 0) tokens.getOrNull(p + 1)?.toIntOrNull()?.let { port = it }
            host = tokens.lastOrNull { '@' in it } ?: tokens.last()
        }
        if ('@' in host) {
            user = host.substringBefore('@').ifEmpty { user }
            host = host.substringAfter('@')
        }
        if (host.count { it == ':' } == 1) {
            host.substringAfter(':').toIntOrNull()?.let { port = it }
            host = host.substringBefore(':')
        }
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty() || port !in 1..65535) return null
        return SshConfig(host, port, user, pass)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    // null, якщо ключ зник (наприклад, після відновлення з бекапу)
    private fun decrypt(data: String): String? = runCatching {
        val b = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
}
