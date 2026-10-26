package com.mitool.terminal

import android.content.Context
import java.io.File
import java.io.IOException

class Router(
    private val ctx: Context,
    private val hub: UsbHub,
    private val files: Attachments,
    private val log: Log
) {
    @Volatile private var link: UsbLink? = null

    fun stop() { link?.close() }

    fun run(line: String) {
        val t = tokenize(line)
        if (t.isEmpty()) return
        try {
            when (t[0]) {
                "help", "?" -> help()
                "adb" -> adb(t.drop(1))
                "fastboot" -> fb(t.drop(1))
                "files" -> {
                    if (files.map.isEmpty()) log.ln("No attached files. Press the paperclip button.", L_I)
                    for ((n, f) in files.map) log.ln("$n  (${humanSize(f.length())})", L_N)
                }
                else -> log.ln("Unknown command '${t[0]}'. Type help", L_E)
            }
        } catch (e: Exception) {
            log.ln("error: ${e.message}", L_E)
        } finally {
            link?.close()
            link = null
        }
    }

    private fun help() {
        log.ln(
            "Commands\n" +
            "  adb devices\n" +
            "  adb shell <cmd>              e.g. adb shell getprop ro.product.model\n" +
            "  adb reboot [bootloader|recovery|sideload|fastboot]\n" +
            "  adb push <file> [remote]     default: /sdcard/Download/\n" +
            "  adb install <apk>\n" +
            "  adb sideload <ota.zip>\n" +
            "  adb logcat -d\n" +
            "  fastboot devices\n" +
            "  fastboot getvar <name|all>\n" +
            "  fastboot reboot [bootloader|recovery|fastboot]\n" +
            "  fastboot flash <partition> <file>\n" +
            "  fastboot boot <file>\n" +
            "  fastboot erase <partition>\n" +
            "  fastboot flashing unlock|lock\n" +
            "  fastboot oem <cmd>\n" +
            "  fastboot set_active a|b\n" +
            "  fastboot continue\n" +
            "  files   clear   help\n\n" +
            "Attach a file with the paperclip. Then use its name, @ or just leave the file argument empty.\n" +
            "Use -s <serial> after adb/fastboot when several phones are connected.", L_I
        )
    }

    private fun fileArg(s: String?): File {
        val f = if (s == null) files.last else (files.byName(s) ?: File(s))
        if (f == null || !f.exists()) throw IOException("No file. Press the paperclip to attach one.")
        return f
    }

    private fun pick(list: List<Target>, serial: String?, what: String): Target {
        if (list.isEmpty()) {
            throw IOException("No $what device found. Connect the target phone with an OTG cable" +
                (if (what == "adb") " and enable USB debugging." else " (reboot it to bootloader)."))
        }
        if (serial != null) return list.firstOrNull { hub.serialOf(it.dev) == serial } ?: throw IOException("Device $serial not found")
        return list.first()
    }

    private fun quoteJoin(a: List<String>): String =
        a.joinToString(" ") { if (it.contains(' ')) "'$it'" else it }

    // ---------------- adb ----------------
    private fun adb(args0: List<String>) {
        var a = args0
        var serial: String? = null
        if (a.size >= 2 && a[0] == "-s") { serial = a[1]; a = a.drop(2) }
        if (a.isEmpty()) { help(); return }
        val targets = hub.scan().filter { it.kind == Kind.ADB }

        if (a[0] == "devices") {
            log.ln("List of devices attached", L_N)
            for (t in targets) {
                val l = hub.open(t)
                if (l == null) { log.ln("${hub.serialOf(t.dev)}\tno permissions", L_E); continue }
                link = l
                val ad = Adb(l, File(ctx.filesDir, "adbkey.pk8"), log)
                val state = try {
                    if (ad.connect()) ad.banner.substringBefore("::") else "offline"
                } catch (e: Exception) { "unauthorized" }
                log.ln("${hub.serialOf(t.dev)}\t$state", L_N)
                l.close()
                link = null
            }
            return
        }
        if (a[0] == "start-server" || a[0] == "kill-server") {
            log.ln("(no server needed - this app talks to the phone directly over USB)", L_I)
            return
        }

        val t = pick(targets, serial, "adb")
        val l = hub.open(t) ?: throw IOException("Cannot open the USB device (permission denied?)")
        link = l
        val ad = Adb(l, File(ctx.filesDir, "adbkey.pk8"), log)
        log.ln("Connecting...", L_I)
        if (!ad.connect()) throw IOException("adb connection refused (unauthorized)")

        when (a[0]) {
            "shell" -> {
                if (a.size < 2) throw IOException("usage: adb shell <command>")
                ad.shellCmd(quoteJoin(a.drop(1)))
            }
            "logcat" -> ad.shellCmd("logcat " + quoteJoin(a.drop(1)))
            "reboot" -> ad.reboot(a.getOrNull(1) ?: "")
            "push" -> {
                val f = fileArg(a.getOrNull(1))
                var remote = a.getOrNull(2) ?: ("/sdcard/Download/" + f.name)
                if (remote.endsWith("/")) remote += f.name
                ad.push(f, remote)
            }
            "install" -> {
                val f = fileArg(a.getOrNull(1))
                val remote = "/data/local/tmp/" + f.name.replace(' ', '_')
                ad.push(f, remote)
                ad.shellCmd("pm install -r '$remote'")
                ad.shellCmd("rm '$remote'")
            }
            "sideload" -> ad.sideload(fileArg(a.getOrNull(1)))
            else -> throw IOException("unsupported: adb ${a[0]}  (type help)")
        }
    }

    // ---------------- fastboot ----------------
    private fun fb(args0: List<String>) {
        var a = args0
        var serial: String? = null
        if (a.size >= 2 && a[0] == "-s") { serial = a[1]; a = a.drop(2) }
        if (a.isEmpty()) { help(); return }
        val targets = hub.scan().filter { it.kind == Kind.FASTBOOT }

        if (a[0] == "devices") {
            for (t in targets) log.ln("${hub.serialOf(t.dev)}\tfastboot", L_N)
            if (targets.isEmpty()) log.ln("(no fastboot device)", L_I)
            return
        }

        val t = pick(targets, serial, "fastboot")
        val l = hub.open(t) ?: throw IOException("Cannot open the USB device (permission denied?)")
        link = l
        val f = Fastboot(l, log)

        when (a[0]) {
            "getvar" -> {
                val v = a.getOrNull(1) ?: throw IOException("usage: fastboot getvar <name|all>")
                val r = f.getvar(v)
                if (v != "all") log.ln("$v: $r", L_N)
                log.ln("OKAY", L_OK)
            }
            "reboot" -> {
                val m = a.getOrNull(1)
                f.cmd(if (m == null) "reboot" else "reboot-$m")
                log.ln("Rebooting...", L_OK)
            }
            "reboot-bootloader" -> { f.cmd("reboot-bootloader"); log.ln("Rebooting to bootloader...", L_OK) }
            "continue" -> { f.cmd("continue"); log.ln("OKAY", L_OK) }
            "flash" -> {
                val part = a.getOrNull(1) ?: throw IOException("usage: fastboot flash <partition> <file>")
                f.flash(part, fileArg(a.getOrNull(2)))
            }
            "boot" -> {
                val file = fileArg(a.getOrNull(1))
                f.download(file)
                f.cmd("boot")
                log.ln("OKAY - booting", L_OK)
            }
            "erase" -> {
                val part = a.getOrNull(1) ?: throw IOException("usage: fastboot erase <partition>")
                f.cmd("erase:$part")
                log.ln("OKAY - erased '$part'", L_OK)
            }
            "oem" -> { f.cmd("oem " + a.drop(1).joinToString(" ")); log.ln("OKAY", L_OK) }
            "flashing" -> { f.cmd("flashing " + a.drop(1).joinToString(" ")); log.ln("OKAY", L_OK) }
            "set_active" -> {
                f.cmd("set_active:" + (a.getOrNull(1) ?: throw IOException("usage: fastboot set_active a|b")))
                log.ln("OKAY", L_OK)
            }
            else -> throw IOException("unsupported: fastboot ${a[0]}  (type help)")
        }
    }
}
