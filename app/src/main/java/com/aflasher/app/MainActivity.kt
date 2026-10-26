package com.aflasher.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val bg = Color.parseColor("#0D1117")
    private val card = Color.parseColor("#161B22")
    private val line = Color.parseColor("#30363D")
    private val orange = Color.parseColor("#FF6900")
    private val grey = Color.parseColor("#8B949E")
    private val green = Color.parseColor("#3FB950")
    private val red = Color.parseColor("#FF7B72")

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var attachBar: LinearLayout
    private lateinit var attachText: TextView
    private lateinit var hub: UsbHub
    private lateinit var router: Router
    private lateinit var attach: Attachments

    private lateinit var cmdPage: ScrollView
    private lateinit var termPage: LinearLayout
    private lateinit var tabCmd: TextView
    private lateinit var tabTerm: TextView
    private lateinit var connCard: LinearLayout
    private lateinit var connTitle: TextView
    private lateinit var connDetail: TextView
    private lateinit var connBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var creditFab: IconButton
    private var pendingRun: String? = null

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val log: Log = { s, lvl -> runOnUiThread { appendOut(s, lvl) } }
    private val quiet: Log = { _, _ -> }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val msg = if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "USB device attached" else "USB device detached"
            appendOut(msg + "\n", L_I)
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setStroke(dp(1), stroke)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        hub = UsbHub(this)
        attach = Attachments(File(cacheDir, "attach"))
        router = Router(this, hub, attach, log)
        buildUi()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, f)
        appendOut("A Flasher\n", L_CMD)
        appendOut("Connect the target phone with an OTG cable. Type help for commands.\n\n", L_I)
    }

    override fun onResume() { super.onResume(); startMonitor() }

    override fun onPause() { monitoring = false; super.onPause() }

    override fun onDestroy() {
        monitoring = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        router.stop()
        router.abortMonitor()
        super.onDestroy()
    }

    // ---------------- connection monitor ----------------
    private class ConnState(val color: Int, val title: String, val detail: String, val settings: Boolean)

    @Volatile private var monitoring = false
    private var gen = 0
    private val authOk = HashSet<String>()
    private val asked = HashSet<String>()
    private val labels = HashMap<String, String>()
    private var lastState: ConnState? = null

    private val otgHelp =
        "1. Turn on OTG on THIS phone:\n" +
        "   - Xiaomi / Redmi / POCO: Settings > Additional settings > OTG connection\n" +
        "   - Oppo / Realme / Vivo / Infinix / Tecno: Settings > Additional settings (or Connection & sharing) > OTG\n" +
        "   - Samsung / Pixel / Motorola: automatic, nothing to turn on\n" +
        "2. Plug the OTG adapter into this phone, then connect the cable to the target phone.\n" +
        "3. On the target phone: Developer options > USB debugging, and set USB mode to File transfer."

    private fun startMonitor() {
        if (monitoring) return
        monitoring = true
        val my = ++gen
        val t = Thread {
            while (monitoring && gen == my) {
                val s = try {
                    computeState()
                } catch (e: Exception) {
                    ConnState(orange, "Checking...", e.message ?: "", false)
                }
                lastState = s
                runOnUiThread { showConn(s) }
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun computeState(): ConnState {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            return ConnState(red, "USB host not supported", "This phone has no OTG / USB host support, so it cannot control other phones.", false)
        }
        val all = hub.mgr.deviceList.values.toList()
        val names = all.map { it.deviceName }.toSet()
        authOk.retainAll(names)
        asked.retainAll(names)
        if (all.isEmpty()) return ConnState(orange, "Waiting for device...", otgHelp, true)

        val targets = hub.scan()
        val fb = targets.firstOrNull { it.kind == Kind.FASTBOOT }
        if (fb != null) {
            val d = fb.dev
            if (!hub.mgr.hasPermission(d) && asked.add(d.deviceName)) hub.ensurePermission(d)
            val ser = if (hub.mgr.hasPermission(d)) hub.serialOf(d) else "waiting for USB permission"
            return ConnState(green, "Fastboot connected", "Serial: $ser", false)
        }

        val ad = targets.firstOrNull { it.kind == Kind.ADB }
        if (ad != null) {
            val d = ad.dev
            val key = d.deviceName
            if (authOk.contains(key)) return ConnState(green, "ADB connected", labels[key] ?: "authorized", false)
            if (!router.lock.tryLock()) {
                return lastState ?: ConnState(orange, "Busy...", "A command is running.", false)
            }
            try {
                if (!hub.mgr.hasPermission(d)) {
                    if (asked.add(key)) hub.ensurePermission(d)
                    if (!hub.mgr.hasPermission(d)) {
                        return ConnState(orange, "USB permission needed", "Tap OK on the permission popup. Unplug and replug the cable to be asked again.", false)
                    }
                }
                val l = hub.open(ad) ?: return ConnState(orange, "Cannot open device", "Replug the cable and try again.", false)
                router.monLink = l
                try {
                    val a = Adb(l, File(filesDir, "adbkey.pk8"), quiet)
                    if (a.connect(25000)) {
                        authOk.add(key)
                        var label = a.banner.substringBefore("::")
                        if (label == "device") {
                            label = try {
                                (a.shellText("getprop ro.product.manufacturer") + " " + a.shellText("getprop ro.product.model")).trim()
                            } catch (e: Exception) { "device" }
                        }
                        labels[key] = label
                        return ConnState(green, "ADB connected", label, false)
                    }
                    return ConnState(orange, "Waiting for authorization", authHelp, false)
                } catch (e: Exception) {
                    return ConnState(orange, "Waiting for authorization", authHelp, false)
                } finally {
                    router.monLink = null
                    l.close()
                }
            } finally {
                router.lock.unlock()
            }
        }
        return ConnState(
            orange, "Device detected, ADB not available",
            "On the target phone enable Developer options > USB debugging and set USB mode to File transfer, or boot it into fastboot mode.",
            false
        )
    }

    private val authHelp =
        "On the target phone tap 'Allow' on the 'Allow USB debugging?' dialog (tick 'Always allow')."

    private fun showConn(s: ConnState) {
        connCard.background = box(card, s.color, 14)
        connTitle.text = s.title
        connTitle.setTextColor(s.color)
        connDetail.text = s.detail
        connBtn.visibility = if (s.settings) View.VISIBLE else View.GONE
        status.text = "● " + s.title
        status.setTextColor(s.color)
    }

    // ---------------- UI ----------------
    private class Cmd(
        val cmd: String,
        val desc: String,
        val needsFile: Boolean = false,
        val needsArgs: Boolean = false,
        val danger: Boolean = false
    )

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply { text = "▌A "; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        titleRow.addView(TextView(this).apply { text = "Flasher"; setTextColor(orange); textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { text = "● Checking..."; textSize = 12f; setTextColor(grey); typeface = Typeface.MONOSPACE }
        titleCol.addView(titleRow)
        titleCol.addView(status)
        stopBtn = TextView(this).apply {
            text = "Stop"; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { stopFlash() }
        }
        setRunning(false)
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(stopBtn, LinearLayout.LayoutParams(-2, dp(36)))
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

        // attached file bar
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

        val frame = FrameLayout(this)

        // ---------- commands page ----------
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16)) }

        connCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        connTitle = TextView(this).apply { text = "Checking..."; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(grey) }
        connDetail = TextView(this).apply { textSize = 12.5f; setTextColor(grey); setPadding(0, dp(6), 0, 0) }
        connBtn = pill("Open Settings", Color.WHITE) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        connBtn.visibility = View.GONE
        connCard.background = box(card, line, 14)
        connCard.addView(connTitle)
        connCard.addView(connDetail)
        connCard.addView(connBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        list.addView(connCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        addSection(list, "ESSENTIALS", listOf(
            Cmd("adb devices", "List connected ADB devices"),
            Cmd("fastboot devices", "List connected fastboot devices"),
            Cmd("adb reboot bootloader", "Reboot to bootloader"),
            Cmd("adb reboot recovery", "Reboot to recovery"),
            Cmd("fastboot reboot", "Reboot the phone"),
            Cmd("fastboot reboot bootloader", "Reboot to bootloader again")
        ))

        list.addView(attachRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        addSection(list, "FASTBOOT", listOf(
            Cmd("fastboot reboot bootloader", "Reboot to bootloader"),
            Cmd("fastboot reboot fastboot", "Reboot to fastbootd"),
            Cmd("fastboot getvar all", "Show bootloader variables"),
            Cmd("fastboot oem device-info", "Show bootloader device info"),
            Cmd("fastboot reboot", "Reboot device"),
            Cmd("fastboot reboot recovery", "Reboot to recovery"),
            Cmd("fastboot reboot edl", "Reboot to EDL mode")
        ))

        addSection(list, "FLASH & INSTALL", listOf(
            Cmd("fastboot flash boot", "Flash boot image", needsFile = true),
            Cmd("fastboot flash init_boot", "Flash init_boot image", needsFile = true),
            Cmd("fastboot flash vendor_boot", "Flash vendor_boot image", needsFile = true),
            Cmd("fastboot flash recovery", "Flash recovery image", needsFile = true),
            Cmd("fastboot flash dtbo", "Flash dtbo image", needsFile = true),
            Cmd("fastboot flash vbmeta", "Flash vbmeta image", needsFile = true),
            Cmd("fastboot flash vbmeta --disable-verity --disable-verification", "Flash vbmeta with verity and verification off", needsFile = true),
            Cmd("fastboot boot", "Boot an image once, without flashing", needsFile = true),
            Cmd("adb sideload", "Install an OTA / ROM zip in sideload mode", needsFile = true),
            Cmd("adb install", "Install an APK", needsFile = true),
            Cmd("adb push", "Copy a file to /sdcard/Download", needsFile = true)
        ))

        addSection(list, "DEVICE INFO", listOf(
            Cmd("adb shell getprop ro.product.model", "Model name"),
            Cmd("adb shell getprop ro.build.version.release", "Android version"),
            Cmd("adb shell dumpsys battery", "Battery status"),
            Cmd("adb shell df -h /data", "Storage usage"),
            Cmd("adb shell pm list packages -3", "Installed user apps"),
            Cmd("fastboot getvar all", "All bootloader variables"),
            Cmd("fastboot getvar current-slot", "Active A/B slot"),
            Cmd("fastboot getvar unlocked", "Bootloader unlock state"),
            Cmd("fastboot getvar product", "Product codename")
        ))

        addSection(list, "TOOLS", listOf(
            Cmd("adb screenshot", "Save a screenshot to the app folder"),
            Cmd("adb pull", "Copy a file from the phone", needsArgs = true),
            Cmd("adb uninstall", "Remove an app by package name", needsArgs = true),
            Cmd("adb shell", "Run any shell command", needsArgs = true),
            Cmd("adb logcat -d", "Dump the system log"),
            Cmd("adb reboot fastboot", "Reboot to fastbootd"),
            Cmd("fastboot reboot fastboot", "Reboot to fastbootd from fastboot"),
            Cmd("fastboot set_active", "Switch A/B slot (a or b)", needsArgs = true),
            Cmd("fastboot oem", "Send an OEM command", needsArgs = true)
        ))

        addSection(list, "DANGER ZONE", listOf(
            Cmd("fastboot flashing unlock", "Unlock bootloader (wipes all data)", danger = true),
            Cmd("fastboot flashing lock", "Lock bootloader (may wipe data)", danger = true),
            Cmd("fastboot erase userdata", "Erase user data", danger = true),
            Cmd("fastboot erase cache", "Erase cache", danger = true)
        ))

        addSection(list, "OTHER", listOf(Cmd("help", "Show all commands")))

        cmdPage = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        frame.addView(cmdPage, FrameLayout.LayoutParams(-1, -1))

        // ---------- terminal page ----------
        termPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        tools.addView(pill("Copy", Color.parseColor("#C9D1D9")) { copyLog() })
        tools.addView(pill("Clear", Color.parseColor("#C9D1D9")) { out.text = "" }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        termPage.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

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
        val go = IconButton(this, IconButton.PLAY, orange).apply {
            setOnClickListener { submit() }
        }
        row.addView(clip, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(input, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row.addView(go, LinearLayout.LayoutParams(dp(44), dp(44)))
        termPage.addView(row, LinearLayout.LayoutParams(-1, -2))
        frame.addView(termPage, FrameLayout.LayoutParams(-1, -1))

        creditFab = IconButton(this, IconButton.INFO, orange).apply { setOnClickListener { showCredits() } }
        frame.addView(creditFab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(8); bottomMargin = dp(16)
        })

        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showTab(false)
    }


    // ---------------- credits ----------------
    private fun showCredits() {
        val links = listOf(
            "Facebook Group" to "https://facebook.com/share/g/1JbRQgSuZz/",
            "Messenger Group (MAX)" to "https://m.me/j/Abado-eMQJTWGYcV",
            "Telegram Group" to "https://t.me/+mU6PXP7hXR9hN2M1",
            "Proof / Verification" to "https://t.me/nayemroot",
            "YouTube Channel" to "https://youtube.com/@bengleraptor",
            "TikTok" to "https://tiktok.com/@naimbinharun"
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(8)) }
        col.addView(TextView(this).apply { text = "Credits"; setTextColor(orange); textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply {
            text = "Stay connected with us and get new updates by joining the social media and community groups below."
            setTextColor(Color.parseColor("#C9D1D9")); textSize = 13.5f; setPadding(0, dp(8), 0, dp(8))
        })
        for ((label, url) in links) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = box(card, line, 12)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (e: Exception) { Toast.makeText(this@MainActivity, "Cannot open link", Toast.LENGTH_SHORT).show() }
                }
            }
            row.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD })
            row.addView(TextView(this).apply { text = url; setTextColor(Color.parseColor("#79C0FF")); textSize = 12f; typeface = Typeface.MONOSPACE })
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        col.addView(TextView(this).apply {
            text = "Thank you for staying with us. Your support and cooperation mean a lot to us."
            setTextColor(grey); textSize = 12.5f; setPadding(0, dp(14), 0, dp(4))
        })
        val sv = ScrollView(this).apply { background = box(bg, line, 18); addView(col) }
        val dlg = AlertDialog.Builder(this)
            .setView(sv)
            .setPositiveButton("Close", null)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(orange)
    }

    private fun addSection(list: LinearLayout, title: String, cmds: List<Cmd>) {
        list.addView(sectionTitle(title))
        for (c in cmds) list.addView(cmdRow(c), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun tabView(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setOnClickListener { click() }
    }

    private fun showTab(terminal: Boolean) {
        cmdPage.visibility = if (terminal) View.GONE else View.VISIBLE
        termPage.visibility = if (terminal) View.VISIBLE else View.GONE
        creditFab.visibility = if (terminal) View.GONE else View.VISIBLE
        tabCmd.background = if (terminal) null else box(orange, orange, 10)
        tabTerm.background = if (terminal) box(orange, orange, 10) else null
        tabCmd.setTextColor(if (terminal) grey else Color.WHITE)
        tabTerm.setTextColor(if (terminal) Color.WHITE else grey)
        if (terminal) scroll.post { scroll.scrollTo(0, out.height) }
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t; setTextColor(if (t == "DANGER ZONE") red else orange); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
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
        col.addView(TextView(this).apply { text = "Attach file"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply { text = "Pick an image, zip or APK for flash / install / push"; setTextColor(grey); textSize = 12f })
        r.addView(icon)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun cmdRow(c: Cmd): LinearLayout {
        val accent = if (c.danger) red else orange
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(card, if (c.danger) red else line, 14)
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { fillInput(c) }
        }
        col.addView(TextView(this).apply { text = c.cmd; setTextColor(Color.WHITE); textSize = 13.5f; typeface = Typeface.MONOSPACE })
        val tag = if (c.needsFile) "  ·  needs file" else if (c.needsArgs) "  ·  needs arguments" else ""
        col.addView(TextView(this).apply { text = c.desc + tag; setTextColor(grey); textSize = 12f; setPadding(0, dp(2), 0, 0) })
        val play = IconButton(this, if (c.needsArgs) IconButton.EDIT else IconButton.PLAY, accent).apply {
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
        if (c.needsArgs) { fillInput(c); return }
        if (c.needsFile && attach.last == null) {
            pendingRun = c.cmd
            pickFile()
            return
        }
        if (c.danger) {
            AlertDialog.Builder(this)
                .setTitle("Are you sure?")
                .setMessage(c.cmd + "\n\n" + c.desc)
                .setPositiveButton("Run") { _, _ -> showTab(true); runLine(c.cmd) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        showTab(true)
        runLine(c.cmd)
    }

    private fun pill(label: String, color: Int, click: () -> Unit): TextView = TextView(this).apply {
        text = label; setTextColor(color); textSize = 12f; typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        background = box(card, line, 16)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { click() }
    }

    private fun setRunning(on: Boolean) {
        stopBtn.background = if (on) box(red, red, 18) else box(card, line, 18)
        stopBtn.setTextColor(if (on) Color.WHITE else grey)
    }

    private fun stopFlash() {
        if (busy.get()) {
            router.stop()
            log.ln("Stopping...", L_E)
        } else {
            Toast.makeText(this, "Nothing is running", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("A Flasher log", out.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun levelColor(l: Int): Int = when (l) {
        L_I -> Color.parseColor("#79C0FF")
        L_E -> red
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
        setRunning(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exec.execute {
            try {
                router.run(l)
            } finally {
                busy.set(false)
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setRunning(false)
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
