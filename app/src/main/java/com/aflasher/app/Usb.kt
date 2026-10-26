package com.aflasher.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val ACTION_PERM = "com.aflasher.app.USB_PERMISSION"

enum class Kind { ADB, FASTBOOT }

class Target(val dev: UsbDevice, val intf: UsbInterface, val kind: Kind)

class UsbLink(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) {
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size - off): Boolean {
        var o = off
        val end = off + len
        while (o < end) {
            val n = minOf(16384, end - o)
            val r = conn.bulkTransfer(outEp, b, o, n, 15000)
            if (r < 0) return false
            o += r
        }
        return true
    }

    fun read(b: ByteArray, off: Int, len: Int, timeout: Int): Int =
        conn.bulkTransfer(inEp, b, off, minOf(len, 16384), timeout)

    fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}

class UsbHub(private val ctx: Context) {
    val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun scan(): List<Target> {
        val out = ArrayList<Target>()
        for (d in mgr.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 66) {
                    if (f.interfaceProtocol == 1) out.add(Target(d, f, Kind.ADB))
                    else if (f.interfaceProtocol == 3) out.add(Target(d, f, Kind.FASTBOOT))
                }
            }
        }
        return out
    }

    fun serialOf(d: UsbDevice): String {
        if (!mgr.hasPermission(d)) ensurePermission(d)
        return try { d.serialNumber ?: d.deviceName } catch (e: Exception) { d.deviceName }
    }

    fun ensurePermission(d: UsbDevice): Boolean {
        if (mgr.hasPermission(d)) return true
        val latch = CountDownLatch(1)
        var granted = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), flags)
        mgr.requestPermission(d, pi)
        latch.await(60, TimeUnit.SECONDS)
        try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        return granted && mgr.hasPermission(d)
    }

    fun open(t: Target): UsbLink? {
        if (!ensurePermission(t.dev)) return null
        val c = mgr.openDevice(t.dev) ?: return null
        if (!c.claimInterface(t.intf, true)) { c.close(); return null }
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (e in 0 until t.intf.endpointCount) {
            val ep = t.intf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
            }
        }
        if (i == null || o == null) { c.close(); return null }
        return UsbLink(c, t.intf, i, o)
    }
}
