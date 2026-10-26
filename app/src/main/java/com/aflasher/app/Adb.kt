package com.aflasher.app

import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

private const val A_CNXN = 0x4e584e43
private const val A_AUTH = 0x48545541
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_CLSE = 0x45534c43
private const val A_WRTE = 0x45545257

class Adb(private val link: UsbLink, private val keyFile: File, private val log: Log) {
    var maxData = 4096
    var banner = ""
    private var nextId = 1

    class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xff)
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        if (!link.write(h.array())) throw IOException("USB write failed")
        if (data.isNotEmpty() && !link.write(data)) throw IOException("USB write failed")
    }

    private fun recv(timeout: Int = 30000): Msg {
        val hb = ByteArray(24)
        val n = link.read(hb, 0, 24, timeout)
        if (n != 24) throw IOException("USB read failed or timed out")
        val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.getInt()
        val a0 = h.getInt()
        val a1 = h.getInt()
        val len = h.getInt()
        val d = ByteArray(len)
        var got = 0
        while (got < len) {
            val r = link.read(d, got, len - got, timeout)
            if (r <= 0) throw IOException("USB read failed")
            got += r
        }
        return Msg(cmd, a0, a1, d)
    }

    // ---------- auth ----------
    private fun keys(): Pair<RSAPrivateCrtKey, RSAPublicKey> {
        val kf = KeyFactory.getInstance("RSA")
        val priv: RSAPrivateCrtKey
        if (keyFile.exists()) {
            priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
        } else {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            keyFile.writeBytes(kp.private.encoded)
            priv = kp.private as RSAPrivateCrtKey
        }
        val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
        return Pair(priv, pub)
    }

    private fun sign(token: ByteArray, priv: PrivateKey): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val tail = prefix + token
        val p = ByteArray(256)
        p[0] = 0
        p[1] = 1
        val psEnd = 256 - tail.size - 1
        for (i in 2 until psEnd) p[i] = 0xFF.toByte()
        p[psEnd] = 0
        System.arraycopy(tail, 0, p, psEnd + 1, tail.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, priv)
        return c.doFinal(p)
    }

    private fun le(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(len)
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            if (idx >= 0) out[i] = be[idx]
        }
        return out
    }

    private fun pubString(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().add(r32).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64).putInt(n0inv).put(le(n, 256)).put(le(rr, 256)).putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return (b64 + " aflasher@android\u0000").toByteArray()
    }

    fun connect(keyWait: Int = 60000): Boolean {
        val (priv, pub) = keys()
        send(A_CNXN, 0x01000001, 256 * 1024, "host::\u0000".toByteArray())
        var sentSig = false
        var sentKey = false
        while (true) {
            val m = recv(if (sentKey) keyWait else 10000)
            if (m.cmd == A_CNXN) {
                maxData = m.a1.coerceIn(4096, 256 * 1024)
                banner = String(m.data).trim { it <= ' ' }
                return true
            }
            if (m.cmd == A_AUTH && m.a0 == 1) {
                if (!sentSig) {
                    sentSig = true
                    send(A_AUTH, 2, 0, sign(m.data, priv))
                } else if (!sentKey) {
                    sentKey = true
                    log.ln("Tap 'Allow USB debugging' on the target phone...", L_I)
                    send(A_AUTH, 3, 0, pubString(pub))
                } else {
                    return false
                }
            }
        }
    }

    // ---------- streams ----------
    inner class Stream(private val dest: String) {
        private val local = nextId++
        private var remote = 0
        var closed = false
        private val pending = ArrayDeque<ByteArray>()

        fun open() {
            send(A_OPEN, local, 0, (dest + "\u0000").toByteArray())
            while (true) {
                val m = recv(30000)
                if (m.cmd == A_OKAY) { remote = m.a0; return }
                if (m.cmd == A_CLSE) throw IOException("device refused '$dest'")
            }
        }

        fun write(b: ByteArray) {
            send(A_WRTE, local, remote, b)
            while (true) {
                val m = recv(60000)
                if (m.cmd == A_OKAY) return
                if (m.cmd == A_WRTE) { pending.addLast(m.data); send(A_OKAY, local, remote) }
                if (m.cmd == A_CLSE) { closed = true; throw IOException("stream closed by device") }
            }
        }

        fun read(): ByteArray? {
            if (pending.isNotEmpty()) return pending.removeFirst()
            while (true) {
                val m = recv(600000)
                if (m.cmd == A_WRTE) { send(A_OKAY, local, remote); return m.data }
                if (m.cmd == A_CLSE) { closed = true; send(A_CLSE, local, remote); return null }
            }
        }

        fun close() {
            if (!closed) {
                closed = true
                try { send(A_CLSE, local, remote) } catch (_: Exception) {}
            }
        }
    }

    fun shellCmd(cmd: String) {
        val s = Stream("shell:$cmd")
        s.open()
        while (true) {
            val d = s.read() ?: break
            log(String(d).replace("\r\n", "\n"), L_N)
        }
    }

    fun execBytes(dest: String): ByteArray {
        val s = Stream(dest)
        s.open()
        val bo = java.io.ByteArrayOutputStream()
        while (true) {
            val d = s.read() ?: break
            bo.write(d)
        }
        return bo.toByteArray()
    }

    fun shellText(cmd: String): String =
        String(execBytes("shell:$cmd")).replace("\r\n", "\n").trim()

    fun pull(remote: String, dest: File) {
        val s = Stream("sync:")
        s.open()
        val pb = remote.toByteArray()
        s.write(syncHdr("RECV", pb.size) + pb)
        dest.parentFile?.mkdirs()
        var total = 0L
        java.io.FileOutputStream(dest).use { fos ->
            var acc = ByteArray(0)
            while (true) {
                while (acc.size < 8) {
                    val d = s.read() ?: throw IOException("connection closed")
                    acc += d
                }
                val id = String(acc, 0, 4)
                val n = ByteBuffer.wrap(acc, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (id == "DONE") break
                while (acc.size < 8 + n) {
                    val d = s.read() ?: throw IOException("connection closed")
                    acc += d
                }
                if (id == "DATA") {
                    fos.write(acc, 8, n)
                    total += n
                    acc = acc.copyOfRange(8 + n, acc.size)
                } else if (id == "FAIL") {
                    throw IOException("pull failed: " + String(acc, 8, n))
                } else {
                    throw IOException("bad sync reply")
                }
            }
        }
        s.close()
        log.ln("Pulled ${humanSize(total)} -> ${dest.absolutePath}", L_OK)
    }

    fun reboot(arg: String) {
        val s = Stream("reboot:$arg")
        try { s.open(); s.read() } catch (_: Exception) {}
        log.ln("Rebooting...", L_OK)
    }

    private fun syncHdr(id: String, n: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(n).array()

    fun push(f: File, remotePath: String) {
        val s = Stream("sync:")
        s.open()
        val pb = "$remotePath,33206".toByteArray()
        s.write(syncHdr("SEND", pb.size) + pb)
        val total = f.length()
        var sent = 0L
        var lastTen = -1
        val maxChunk = minOf(65536, maxData - 8)
        val buf = ByteArray(maxChunk)
        f.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                s.write(syncHdr("DATA", n) + buf.copyOf(n))
                sent += n
                val ten = if (total > 0) (sent * 10 / total).toInt() else 10
                if (ten != lastTen) {
                    lastTen = ten
                    log.ln("  push ${ten * 10}%  (${humanSize(sent)} / ${humanSize(total)})", L_I)
                }
            }
        }
        s.write(syncHdr("DONE", (System.currentTimeMillis() / 1000).toInt()))
        val r = s.read()
        s.close()
        if (r == null || r.size < 4 || String(r, 0, 4) != "OKAY") {
            throw IOException("push failed: " + (if (r == null) "closed" else String(r)))
        }
        log.ln("$remotePath: pushed (${humanSize(total)})", L_OK)
    }

    fun sideload(f: File) {
        val bs = 65536
        val size = f.length()
        val s = Stream("sideload-host:$size:$bs")
        s.open()
        var lastTen = -1
        RandomAccessFile(f, "r").use { raf ->
            while (true) {
                val d = s.read() ?: break
                val txt = String(d, Charsets.US_ASCII).trim { it <= ' ' }
                if (txt.startsWith("DONEDONE")) {
                    log.ln("Sideload finished", L_OK)
                    break
                }
                val block = txt.toLongOrNull() ?: continue
                val off = block * bs
                if (off >= size) throw IOException("device asked for block outside file")
                val n = minOf(bs.toLong(), size - off).toInt()
                val b = ByteArray(n)
                raf.seek(off)
                raf.readFully(b)
                s.write(b)
                val ten = (off * 10 / size).toInt()
                if (ten != lastTen) {
                    lastTen = ten
                    log.ln("  sideload ${ten * 10}%", L_I)
                }
            }
        }
    }
}
