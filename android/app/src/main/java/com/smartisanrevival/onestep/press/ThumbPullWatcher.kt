package com.smartisanrevival.onestep.press

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.smartisanrevival.onestep.shell.IOneStepShellService
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原版手势：拇指大面积触屏 + 由上向下拖拽 → 呼出一步。
 * R2 设置页原文验证的交互；魅族 main_touch 上报 ABS_MT_TOUCH_MAJOR（max 255），可判接触面积。
 *
 * 与大爆炸 PressWatcher 同一技术路线（Shizuku shell 读 evdev 24 字节帧），
 * 但绑定独立 UserService（onestep-shell），避免抢大爆炸的单流槽位。
 *
 * 触发条件（防误触三闸）：
 *  1. 接触面积 ≥ 档位阈值（原版五档「触发面积」，默认「大」= level 3）
 *  2. 首次达标时手指位于上半屏（状态栏以下 ~ 屏高 40%）——避开通知栏下拉和列表上滑
 *  3. 之后向下位移 ≥ 屏高 18%，横向漂移 < 位移的 50%
 */
class ThumbPullWatcher(
    private val context: Context,
    private val areaLevel: Int,
    private val onTrigger: () -> Unit
) {
    companion object {
        private const val TAG = "OneStepThumb"
        private const val SERVICE_VERSION = 1
        private const val FRAME_BYTES = 24
        private const val COOLDOWN_MS = 1500L

        // 触发面积五档（0 最灵敏…4 最迟钝）。touch_major 满量 255，阈值待真机校准。
        val AREA_THRESHOLDS = floatArrayOf(60f, 95f, 125f, 150f, 180f)
        private const val START_Y_MIN_FRAC = 0.03f
        private const val START_Y_MAX_FRAC = 0.40f
        private const val DRAG_FRAC = 0.18f

        private const val EV_SYN = 0x00
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
    }

    private val running = AtomicBoolean(false)
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastFire = 0L

    // 触摸屏原始量程（getevent -pl 探测后回填）
    private var maxW = 1080
    private var maxH = 2340

    // 协议 B 多槽位状态
    private class Slot {
        var tid = -1
        var x = 0
        var lastX = 0
        var y = 0
        var major = 0f
        var startY = -1       // 达标时的起始 y（-1 = 未建档）
        var fired = false
    }

    private val slots = Array(10) { Slot() }
    private var curSlot = 0

    fun start() {
        if (running.getAndSet(true)) return
        val a = Shizuku.UserServiceArgs(
            ComponentName(context, com.smartisanrevival.onestep.shell.OneStepShellService::class.java)
        ).daemon(false)
            .debuggable(false)
            .version(SERVICE_VERSION)
            .processNameSuffix("onestep-shell")
            .tag("onestep-shell")
        args = a
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IOneStepShellService.Stub.asInterface(binder)
                Thread({
                    try {
                        runLoop(svc)
                    } catch (e: Exception) {
                        Log.e(TAG, "thumb watcher died", e)
                    } finally {
                        running.set(false)
                    }
                }, "onestep-thumb").start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "shell service disconnected")
                running.set(false)
            }
        }
        connection = conn
        Shizuku.bindUserService(a, conn)
    }

    fun stop() {
        running.set(false)
        val a = args
        val c = connection
        if (a != null && c != null) {
            try { Shizuku.unbindUserService(a, c, true) } catch (_: Exception) {}
        }
        connection = null
    }

    private fun runLoop(svc: IOneStepShellService) {
        val probe = svc.runForOutput(arrayOf("getevent", "-pl"))
        val device = parseProbe(probe) ?: run {
            Log.i(TAG, "no touchscreen found")
            return
        }
        Log.i(TAG, "touch device=$device max=($maxW,$maxH) areaLevel=$areaLevel")

        val pfd: ParcelFileDescriptor = svc.streamDevice(device) ?: run {
            Log.i(TAG, "streamDevice returned null")
            return
        }
        val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd))
        val frame = ByteArray(FRAME_BYTES)

        while (running.get()) {
            try {
                input.readFully(frame)
            } catch (e: Exception) {
                Log.i(TAG, "stream ended: ${e.message}")
                break
            }
            readFrame(frame)
        }
    }

    private fun parseProbe(out: String): String? {
        var current: String? = null
        var touch = false
        var best: String? = null
        var bx = 0
        var by = 0
        var cx = 0
        var cy = 0
        fun commit() {
            if (touch && current != null && cx > 0 && cy > 0) { best = current; bx = cx; by = cy }
        }
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.startsWith("add device")) {
                commit()
                current = line.substringAfter(":").trim()
                touch = false; cx = 0; cy = 0
                continue
            }
            if (line.contains("main_touch") || line.contains("goodix") ||
                line.contains("focal") || line.contains("fts")
            ) touch = true
            Regex("ABS_MT_POSITION_X.*max\\s+(\\d+)").find(line)?.let { cx = it.groupValues[1].toInt() }
            Regex("ABS_MT_POSITION_Y.*max\\s+(\\d+)").find(line)?.let { cy = it.groupValues[1].toInt() }
        }
        commit()
        if (bx > 0) maxW = bx
        if (by > 0) maxH = by
        return best
    }

    private fun readFrame(frame: ByteArray) {
        val type = readShort(frame, 16)
        val code = readShort(frame, 18)
        val value = readInt(frame, 20)
        when {
            type == EV_ABS && code == ABS_MT_SLOT -> curSlot = value.coerceIn(0, slots.size - 1)
            type == EV_ABS && code == ABS_MT_TRACKING_ID -> slots[curSlot].tid = value
            type == EV_ABS && code == ABS_MT_POSITION_X -> slots[curSlot].x = value
            type == EV_ABS && code == ABS_MT_POSITION_Y -> slots[curSlot].y = value
            type == EV_ABS && code == ABS_MT_TOUCH_MAJOR -> slots[curSlot].major = value.toFloat()
            type == EV_SYN && code == SYN_REPORT -> onSync()
        }
    }

    private fun onSync() {
        val threshold = AREA_THRESHOLDS.getOrElse(areaLevel) { 150f }
        val startYMin = (maxH * START_Y_MIN_FRAC).toInt()
        val startYMax = (maxH * START_Y_MAX_FRAC).toInt()
        val dragDist = (maxH * DRAG_FRAC).toInt()

        for (s in slots) {
            if (s.tid < 0) continue
            // 达标建档：面积够 + 手指在上半屏 + 还没怎么走
            if (s.startY < 0 && s.major >= threshold && s.y in startYMin..startYMax && !s.fired) {
                s.startY = s.y
                s.lastX = s.x
                Log.i(TAG, "candidate tid=${s.tid} major=${s.major.toInt()} y=${s.y}")
                continue
            }
            if (s.startY >= 0 && !s.fired) {
                val dy = s.y - s.startY
                val dx = kotlin.math.abs(s.x - s.lastX)
                if (dy >= dragDist && dx < dy * 0.5f) {
                    s.fired = true
                    s.startY = -1
                    fire()
                } else if (dy > dragDist * 3) {
                    // 拖了很远还不满足直向条件，放弃这次接触
                    s.fired = true
                    s.startY = -1
                }
            }
            s.lastX = s.x
        }
        // 手指全部抬起时复位
        if (slots.all { it.tid < 0 }) slots.forEach { reset(it) }
    }

    private fun fire() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < COOLDOWN_MS) return
        lastFire = now
        Log.i(TAG, "THUMB PULL triggered")
        main.post { onTrigger() }
    }

    private fun reset(s: Slot) {
        s.tid = -1; s.startY = -1; s.fired = false; s.major = 0f
    }

    private fun readShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
