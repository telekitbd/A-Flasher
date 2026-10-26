package com.mitool.terminal

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val bg = Color.parseColor("#0D1117")
    private val card = Color.parseColor("#161B22")
    private val line = Color.parseColor("#30363D")
    private val orange = Color.parseColor("#FF6900")
    private val grey = Color.parseColor("#8B949E")
    private val green = Color.parseColor("#3FB950")

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var attachBar: LinearLayout
    private lateinit var attachText: TextView
    private lateinit var hub: UsbHub
    private lateinit var router: Router
    private lateinit var attach: Attachments

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val log: Log = { s, lvl -> runOnUiThread { appendOut(s, lvl) } }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val msg = if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "USB device attached" else "USB device detached"
            appendOut(msg + "\n", L_I)
            updateStatus()
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int, r: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1), stroke); cornerRadius(dp(r).toFloat())
    }

    private fun GradientDrawable.cornerRadius(r: Float) { this.cornerRadius = r }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        hub = UsbHub(this)
        attach = Attachments(java.io.File(cacheDir, "attach"))
        router = Router(this, hub, attach, log)
        buildUi()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, f)
        appendOut("MiTool Terminal\n", L_CMD)
        appendOut("Connect the target phone with an OTG cable. Type help for commands.\n\n", L_I)
        updateStatus()
    }

    override fun onResume() { super.onResume(); updateStatus() }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); updateStatus() }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        router.stop()
        super.onDestroy()
    }

    // ---------------- UI ----------------
    private class Cmd(val cmd: String, val desc: String, val needsFile: Boolean = false)

    private lateinit var cmdPage: ScrollView
    private lateinit var termPage: LinearLayout
    private lateinit var tabCmd: TextView
    private lateinit var tabTerm: TextView
    private var pendingRun: String? = null

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "▌Mi"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        }
        val title2 = TextView(this).apply {
            text = "Tool"; setTextColor(orange); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply { textSize = 12f; setTextColor(grey); typeface = Typeface.MONOSPACE }
        val stop = pill("■ Stop", Color.parseColor("#FF7B72")) { router.stop() }
        header.addView(title)
        header.addView(title2)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(status)
        header.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // tabs
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(card, line, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        tabCmd = tabView("☰  Commands") { showTab(false) }
        tabTerm = tabView(">_  Terminal") { showTab(true) }
        tabs.addView(tabCmd, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(tabTerm, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // attached file bar (shared by both tabs)
        attachBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(card, orange, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        attachText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE }
        val x = TextView(this).apply {
            text = "✕"; setTextColor(grey); textSize = 16f; setPadding(dp(12), 0, 0, 0)
            setOnClickListener { attach.clear(); updateAttachBar() }
        }
        attachBar.addView(attachText, LinearLayout.LayoutParams(0, -2, 1f))
        attachBar.addView(x)
        root.addView(attachBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val frame = android.widget.FrameLayout(this)

        // ---------- commands page ----------
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        list.addView(attachRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        list.addView(sectionTitle("ADB  ·  ফোন চালু অবস্থায়"))
        val adbCmds = listOf(
            Cmd("adb devices", "সংযুক্ত ফোনের তালিকা"),
            Cmd("adb shell getprop ro.product.model", "ফোনের মডেল"),
            Cmd("adb reboot", "রিবুট"),
            Cmd("adb reboot bootloader", "বুটলোডারে রিবুট"),
            Cmd("adb reboot recovery", "রিকভারিতে রিবুট"),
            Cmd("adb push", "ফাইল ফোনে পাঠান (📎 লাগবে)", true),
            Cmd("adb install", "APK ইনস্টল (📎 লাগবে)", true),
            Cmd("adb sideload", "OTA zip সাইডলোড (📎 লাগবে)", true),
            Cmd("adb logcat -d", "লগ দেখুন")
        )
        for (c in adbCmds) list.addView(cmdRow(c), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        list.addView(sectionTitle("FASTBOOT  ·  বুটলোডার মোডে"))
        val fbCmds = listOf(
            Cmd("fastboot devices", "সংযুক্ত ফোনের তালিকা"),
            Cmd("fastboot getvar all", "ডিভাইসের সব তথ্য"),
            Cmd("fastboot reboot", "রিবুট"),
            Cmd("fastboot reboot bootloader", "বুটলোডারে রিবুট"),
            Cmd("fastboot flash boot", "boot ফ্ল্যাশ (📎 লাগবে)", true),
            Cmd("fastboot flash recovery", "recovery ফ্ল্যাশ (📎 লাগবে)", true),
            Cmd("fastboot flash vbmeta", "vbmeta ফ্ল্যাশ (📎 লাগবে)", true),
            Cmd("fastboot boot", "ফ্ল্যাশ ছাড়া একবার বুট (📎 লাগবে)", true)
        )
        for (c in fbCmds) list.addView(cmdRow(c), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        list.addView(sectionTitle("অন্যান্য"))
        list.addView(cmdRow(Cmd("help", "সব কমান্ডের তালিকা")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        cmdPage = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        frame.addView(cmdPage, android.widget.FrameLayout.LayoutParams(-1, -1))

        // ---------- terminal page ----------
        termPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        out = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D1D9")); textSize = 12.5f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scroll = ScrollView(this).apply { background = box(Color.parseColor("#010409"), line, 12); addView(out) }
        termPage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val clip = TextView(this).apply {
            text = "📎"; textSize = 20f; gravity = Gravity.CENTER
            background = box(card, line, 22)
            setOnClickListener { pickFile() }
        }
        input = EditText(this).apply {
            hint = "adb devices / fastboot flash boot ..."
            setHintTextColor(grey); setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 14f
            background = box(card, line, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        val go = TextView(this).apply {
            text = "▶"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = box(orange, orange, 22)
            setOnClickListener { submit() }
        }
        row.addView(clip, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(input, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row.addView(go, LinearLayout.LayoutParams(dp(44), dp(44)))
        termPage.addView(row, LinearLayout.LayoutParams(-1, -2))
        frame.addView(termPage, android.widget.FrameLayout.LayoutParams(-1, -1))

        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showTab(false)
    }

    private fun tabView(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setOnClickListener { click() }
    }

    private fun showTab(terminal: Boolean) {
        cmdPage.visibility = if (terminal) View.GONE else View.VISIBLE
        termPage.visibility = if (terminal) View.VISIBLE else View.GONE
        tabCmd.background = if (terminal) null else box(orange, orange, 10)
        tabTerm.background = if (terminal) box(orange, orange, 10) else null
        tabCmd.setTextColor(if (terminal) grey else Color.WHITE)
        tabTerm.setTextColor(if (terminal) Color.WHITE else grey)
        if (terminal) scroll.post { scroll.scrollTo(0, out.height) }
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t; setTextColor(orange); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(18), 0, 0)
    }

    private fun attachRow(): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(card, orange, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { pickFile() }
        }
        val icon = TextView(this).apply { text = "📎"; textSize = 22f; setPadding(0, 0, dp(12), 0) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "ফাইল অ্যাটাচ করুন"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply { text = "flash / push / install / sideload এর জন্য"; setTextColor(grey); textSize = 12f })
        r.addView(icon)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun cmdRow(c: Cmd): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(card, line, 14)
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { fillInput(c) }
        }
        col.addView(TextView(this).apply { text = c.cmd; setTextColor(Color.WHITE); textSize = 13.5f; typeface = Typeface.MONOSPACE })
        col.addView(TextView(this).apply { text = c.desc; setTextColor(grey); textSize = 12f; setPadding(0, dp(2), 0, 0) })
        val play = TextView(this).apply {
            text = "▶"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(orange) }
            setOnClickListener { runFromList(c) }
        }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(play, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        return r
    }

    private fun fillInput(c: Cmd) {
        input.setText(c.cmd + " ")
        input.setSelection(input.text.length)
        showTab(true)
        input.requestFocus()
    }

    private fun runFromList(c: Cmd) {
        if (c.needsFile && attach.last == null) {
            pendingRun = c.cmd
            pickFile()
            return
        }
        showTab(true)
        runLine(c.cmd)
    }

    private fun pill(label: String, color: Int, click: () -> Unit): TextView = TextView(this).apply {
        text = label; setTextColor(color); textSize = 12f; typeface = Typeface.MONOSPACE
        background = box(card, line, 16)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { click() }
    }

    private fun levelColor(l: Int): Int = when (l) {
        L_I -> Color.parseColor("#79C0FF")
        L_E -> Color.parseColor("#FF7B72")
        L_OK -> green
        L_CMD -> orange
        else -> Color.parseColor("#C9D1D9")
    }

    private fun appendOut(s: String, lvl: Int) {
        if (s.isEmpty()) return
        val sp = SpannableString(s)
        sp.setSpan(ForegroundColorSpan(levelColor(lvl)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append(sp)
        if (out.length() > 300000) (out.text as? Editable)?.delete(0, 100000)
        scroll.post { scroll.scrollTo(0, out.height) }
    }

    private fun updateStatus() {
        val t = hub.scan()
        if (t.isEmpty()) {
            status.text = "● no device"; status.setTextColor(grey)
        } else {
            status.text = "● adb:" + t.count { it.kind == Kind.ADB } + " fb:" + t.count { it.kind == Kind.FASTBOOT }
            status.setTextColor(green)
        }
    }

    private fun updateAttachBar() {
        val last = attach.last
        if (last == null) { attachBar.visibility = View.GONE; return }
        attachBar.visibility = View.VISIBLE
        val more = if (attach.map.size > 1) "  (+${attach.map.size - 1})" else ""
        attachText.text = "📎 ${last.name}  ·  ${humanSize(last.length())}$more"
    }

    // ---------------- commands ----------------
    private fun submit() {
        val l = input.text.toString().trim()
        if (l.isEmpty()) return
        input.setText("")
        runLine(l)
    }

    private fun runLine(l: String) {
        if (l == "clear") { out.text = ""; return }
        if (!busy.compareAndSet(false, true)) {
            log.ln("busy - wait for the running command or press Stop", L_E)
            return
        }
        log.ln("$ $l", L_CMD)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exec.execute {
            try {
                router.run(l)
            } finally {
                busy.set(false)
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    updateStatus()
                }
            }
        }
    }

    // ---------------- file attach ----------------
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 77)
    }

    private fun queryName(u: Uri): String? =
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 77) return
        if (res != RESULT_OK || data == null) { pendingRun = null; return }
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        exec.execute {
            for (u in uris) {
                val name = queryName(u) ?: "file.bin"
                log.ln("Copying '$name'...", L_I)
                try {
                    val ins = contentResolver.openInputStream(u) ?: throw java.io.IOException("cannot open file")
                    val f = ins.use { attach.add(name, it) }
                    log.ln("Attached: $name (${humanSize(f.length())})", L_OK)
                    runOnUiThread {
                        updateAttachBar()
                        val cur = input.text.toString()
                        if (cur.isNotBlank()) {
                            val nm = if (name.contains(' ')) "'$name'" else name
                            input.setText(cur.trimEnd() + " " + nm)
                            input.setSelection(input.text.length)
                        }
                    }
                } catch (e: Exception) {
                    log.ln("attach failed: ${e.message}", L_E)
                }
            }
            runOnUiThread {
                val p = pendingRun
                pendingRun = null
                if (p != null && attach.last != null) { showTab(true); runLine(p) }
            }
        }
    }
}
