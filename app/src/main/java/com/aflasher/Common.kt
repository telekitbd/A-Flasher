package com.aflasher

import java.io.File
import java.io.InputStream

typealias Log = (String, Int) -> Unit

const val L_N = 0
const val L_I = 1
const val L_E = 2
const val L_OK = 3
const val L_CMD = 4

fun Log.ln(s: String, l: Int = 0) = this(s + "\n", l)

fun tokenize(s: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var q: Char? = null
    var has = false
    for (c in s) {
        if (q != null) {
            if (c == q) { q = null } else { sb.append(c) }
        } else if (c == '"' || c == '\'') {
            q = c; has = true
        } else if (c.isWhitespace()) {
            if (sb.isNotEmpty() || has) { out.add(sb.toString()); sb.setLength(0); has = false }
        } else {
            sb.append(c)
        }
    }
    if (sb.isNotEmpty() || has) out.add(sb.toString())
    return out
}

fun humanSize(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

class Attachments(private val dir: File) {
    val map = LinkedHashMap<String, File>()
    var last: File? = null

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun add(name: String, ins: InputStream): File {
        val f = File(dir, name.replace('/', '_'))
        f.outputStream().use { o -> ins.copyTo(o, 1 shl 20) }
        map[name] = f
        last = f
        return f
    }

    fun byName(n: String): File? = if (n == "@" || n == "@file") last else map[n]

    fun clear() {
        map.values.forEach { it.delete() }
        map.clear()
        last = null
    }
}
