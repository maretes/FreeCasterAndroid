package com.freecaster.app

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** З'єднання з цільовим сайтом (у нас це канал усередині SSH). */
class Upstream(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/**
 * Локальний SOCKS-сервер на 127.0.0.1 (SOCKS5, а також SOCKS4/4a).
 * Кожне з'єднання передається в [open], тобто як у `ssh -D`.
 */
class SocksServer(private val open: (host: String, port: Int) -> Upstream) {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()

    val port: Int get() = server.localPort

    fun start() {
        thread(name = "socks-accept", isDaemon = true) {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    pool.execute { handle(s) }
                } catch (e: Exception) {
                    s.close()
                }
            }
        }
    }

    fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private class Target(val version: Int, val host: String, val port: Int)

    private fun handle(client: Socket) = client.use {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000 // лише на час рукостискання
            val inp = DataInputStream(BufferedInputStream(client.getInputStream()))
            val out = client.getOutputStream()
            val target = when (inp.readUnsignedByte()) {
                5 -> socks5(inp, out)
                4 -> socks4(inp)
                else -> null
            } ?: return@use

            val up = try {
                open(target.host, target.port)
            } catch (e: Exception) {
                reply(out, target.version, ok = false)
                return@use
            }
            up.use {
                reply(out, target.version, ok = true)
                client.soTimeout = 0
                val uplink = thread(name = "socks-up", isDaemon = true) {
                    try {
                        copy(inp, up.output)
                    } catch (_: IOException) {
                    } finally {
                        up.close()
                        runCatching { client.shutdownInput() }
                    }
                }
                try {
                    copy(up.input, out)
                } catch (_: IOException) {
                }
                runCatching { client.close() }
                uplink.join(1000)
            }
        } catch (_: IOException) {
        }
    }

    private fun socks5(inp: DataInputStream, out: OutputStream): Target? {
        val n = inp.readUnsignedByte()
        inp.readFully(ByteArray(n)) // методи автентифікації: приймаємо без пароля
        out.write(byteArrayOf(5, 0))
        out.flush()

        if (inp.readUnsignedByte() != 5) return null
        val cmd = inp.readUnsignedByte()
        inp.readUnsignedByte()
        val host = when (inp.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { inp.readFully(it) }).hostAddress
            3 -> String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { inp.readFully(it) }).hostAddress
            else -> null
        }
        val port = inp.readUnsignedShort()
        if (cmd != 1 || host == null) { // підтримуємо лише CONNECT
            out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            return null
        }
        return Target(5, host, port)
    }

    private fun socks4(inp: DataInputStream): Target? {
        val cmd = inp.readUnsignedByte()
        val port = inp.readUnsignedShort()
        val ip = ByteArray(4).also { inp.readFully(it) }
        readCString(inp) // user id
        // SOCKS4a: 0.0.0.x означає, що далі йде ім'я хоста
        val host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()) {
            readCString(inp)
        } else {
            InetAddress.getByAddress(ip).hostAddress
        }
        if (cmd != 1 || host.isNullOrEmpty()) return null
        return Target(4, host, port)
    }

    private fun readCString(inp: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.readUnsignedByte()
            if (b == 0) return sb.toString()
            if (sb.length > 255) throw IOException("too long")
            sb.append(b.toChar())
        }
    }

    private fun reply(out: OutputStream, version: Int, ok: Boolean) {
        val r = if (version == 5) {
            byteArrayOf(5, (if (ok) 0 else 1).toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
        } else {
            byteArrayOf(0, (if (ok) 0x5A else 0x5B).toByte(), 0, 0, 0, 0, 0, 0)
        }
        out.write(r)
        out.flush()
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) return
            to.write(buf, 0, n)
            to.flush()
        }
    }
}
