package com.aflasher.app

import java.io.File
import java.io.IOException

class Fastboot(private val link: UsbLink, private val log: Log) {
    private val buf = ByteArray(4096)

    fun cmd(c: String): String {
        if (!link.write(c.toByteArray())) throw IOException("USB write failed")
        return response()
    }

    private fun response(): String {
        while (true) {
            val n = link.read(buf, 0, buf.size, 120000)
            if (n < 4) throw IOException("USB read failed or timed out")
            val s = String(buf, 0, n)
            val tag = s.substring(0, 4)
            val rest = s.substring(4).trim { it <= ' ' }
            when (tag) {
                "INFO" -> log.ln("(bootloader) $rest", L_I)
                "TEXT" -> log.ln(rest, L_N)
                "OKAY" -> return rest
                "DATA" -> return "DATA$rest"
                "FAIL" -> throw IOException("FAILED: $rest")
                else -> throw IOException("unknown reply: $s")
            }
        }
    }

    fun getvar(v: String): String = cmd("getvar:$v")

    private fun maxDownload(): Long {
        return try {
            val v = getvar("max-download-size").trim()
            if (v.startsWith("0x")) v.substring(2).toLong(16) else v.toLong()
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    fun download(f: File) {
        val size = f.length()
        val r = cmd("download:" + String.format("%08x", size))
        if (!r.startsWith("DATA")) throw IOException("unexpected reply: $r")
        val expect = r.substring(4).toLong(16)
        if (expect != size) throw IOException("bootloader expected $expect bytes, file has $size")
        val chunk = ByteArray(1 shl 20)
        var sent = 0L
        var lastTen = -1
        f.inputStream().use { ins ->
            while (true) {
                val n = ins.read(chunk)
                if (n <= 0) break
                if (!link.write(chunk, 0, n)) throw IOException("USB write failed")
                sent += n
                val ten = (sent * 10 / size).toInt()
                if (ten != lastTen) {
                    lastTen = ten
                    log.ln("  sending ${ten * 10}%  (${humanSize(sent)} / ${humanSize(size)})", L_I)
                }
            }
        }
        response()
    }

    fun flash(part: String, f: File) {
        val max = maxDownload()
        if (f.length() > max) {
            throw IOException("File (${humanSize(f.length())}) is bigger than max-download-size (${humanSize(max)}). Use a sparse image or a smaller file.")
        }
        log.ln("Sending '$part' (${humanSize(f.length())})", L_I)
        download(f)
        log.ln("Writing '$part'...", L_I)
        cmd("flash:$part")
        log.ln("OKAY - flashed '$part'", L_OK)
    }
}
