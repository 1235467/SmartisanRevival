package com.smartisanrevival.bang.press

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smartisanrevival.bang.shell.IShellService
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 系统输入层拇指按压：shell 身份直读 /dev/input 的 evdev 事件流。
 *
 * 不用 `getevent -ql`：它的文本输出在管道里会整块缓冲，实机上读不到实时行。
 * 二进制帧固定 24 字节（timeval 16 + type 2 + code 2 + value 4）。
 *
 * 手指按住不动时内核不再上报事件，所以不能只在事件到达时判断时长，
 * 必须在下按那一刻挂定时器，到点主动检查手指是否仍在原处。
 */
class PressWatcher(
    private val displayW: Int,
    private val displayH: Int,
    private val pressTimeoutMs: Long,
    /** 按住约 130ms、菜单还没弹出来时取一次字。QQ/微信长按会把自己的菜单盖上来。 */
    private val onStable: (x: Int, y: Int, gesture: Int) -> Unit,
    private val onPress: (x: Int, y: Int, durationMs: Long, gesture: Int) -> Unit
) {
    companion object {
        private const val TAG = "BangPress"
        private const val MOVE_SLOP_PX = 40
        private const val SERVICE_VERSION = 4

        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0
        private const val BTN_TOUCH = 0x14a
        private const val ABS_MT_SLOT = 0x2f
        /** 接触椭圆长轴。这块屏 0–255，指尖轻点通常 <20，拇指肚按住会到 30+。 */
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        /** 按下后再过这么久取字。比系统长按短，聊天软件的复制菜单还没盖上来。 */
        private const val PREVIEW_DELAY_MS = 180L
        /**
         * 原版「触发面积」中间档。面积越小越容易炸；32/255 能挡指尖轻点，
         * 拇指按住文字过得去。本机没有 ABS_MT_PRESSURE。
         */
        private const val MIN_TOUCH_MAJOR = 32

        private const val FRAME_BYTES = 24
    }

    private val running = AtomicBoolean(false)
    private val maxX = AtomicInteger(displayW - 1)
    private val maxY = AtomicInteger(displayH - 1)
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private val shellRef = AtomicReference<IShellService?>(null)
    private val main = Handler(Looper.getMainLooper())

    // 读取线程写、主线程定时器读，必须跨线程可见
    @Volatile private var posX = 0
    @Volatile private var posY = 0
    @Volatile private var pressed = false
    @Volatile private var anchored = false
    @Volatile private var moved = false
    @Volatile private var fired = false
    @Volatile private var gestureId = 0
    @Volatile private var reading = false
    private val gestureSeq = AtomicInteger()
    private var originX = 0
    private var originY = 0
    private var downAt = 0L
    private var currentSlot = 0
    private var activeSlot = -1
    private var haveX = false
    private var haveY = false
    private var scrollRaw = 0
    private var lastRawX = 0
    private var lastRawY = 0
    private var anchorWaits = 0
    private var peakMajor = 0
    private var minTouchMajor = MIN_TOUCH_MAJOR
    private val slotX = IntArray(10) { -1 }
    private val slotY = IntArray(10) { -1 }
    private val slotMajor = IntArray(10)

    fun isRunning(): Boolean = running.get()

    /** 正在从触摸设备读事件。绑定成功但线程没起来时，这个是 false。 */
    fun isReading(): Boolean = reading

    fun start(context: Context) {
        if (running.getAndSet(true)) return
        val a = Shizuku.UserServiceArgs(
            ComponentName(context, com.smartisanrevival.bang.shell.ShellService::class.java)
        ).daemon(false)
            .debuggable(false)
            .version(SERVICE_VERSION)
            .processNameSuffix("shell")
            .tag("bang-shell")
        args = a
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IShellService.Stub.asInterface(binder)
                shellRef.set(svc)
                Thread({
                    while (running.get()) {
                        try {
                            if (!runLoop(svc)) break
                        } catch (e: Exception) {
                            Log.e(TAG, "press watcher died", e)
                        }
                        if (!running.get()) break
                        Log.i(TAG, "touch stream ended, reopen in 1s")
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    reading = false
                    running.set(false)
                    Log.i(TAG, "press watcher stopped")
                }, "bang-presswatcher").start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "shell service disconnected")
                shellRef.set(null)
            }
        }
        connection = conn
        try {
            Shizuku.bindUserService(a, conn)
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "bindUserService failed", e)
            throw e
        }
    }

    fun stop() {
        running.set(false)
        main.removeCallbacksAndMessages(null)
        try {
            shellRef.get()?.stopStreaming()
        } catch (_: Exception) {
        }
        val a = args
        val c = connection
        if (a != null && c != null) {
            try {
                Shizuku.unbindUserService(a, c, true)
            } catch (_: Exception) {
            }
        }
        shellRef.set(null)
    }

    fun injectUp(x: Int, y: Int) {
        try {
            shellRef.get()?.injectMotion("UP", x, y)
            Log.i(TAG, "injected UP ($x,$y)")
        } catch (e: Exception) {
            Log.e(TAG, "inject UP failed", e)
        }
    }

    private fun slopRaw(): Int =
        (MOVE_SLOP_PX.toFloat() / displayW * maxX.get()).toInt().coerceAtLeast(8)

    /** @return false 表示没有触摸设备，不要重试。 */
    private fun runLoop(svc: IShellService): Boolean {
        val probe = svc.runForOutput(arrayOf("getevent", "-pl"))
        val device = parseProbe(probe) ?: run {
            Log.i(TAG, "no touchscreen in getevent -pl")
            return false
        }
        Log.i(TAG, "touch device=$device max=(${maxX.get()},${maxY.get()}) timeout=${pressTimeoutMs}ms")
        reading = true

        val pfd: ParcelFileDescriptor = svc.streamDevice(device)
        val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd))
        val frame = ByteArray(FRAME_BYTES)
        var frames = 0

        while (running.get()) {
            try {
                input.readFully(frame)
            } catch (e: Exception) {
                Log.i(TAG, "stream ended after $frames frames: ${e.message}")
                break
            }
            frames++
            readFrame(frame)
        }
        reading = false
        return true
    }

    private fun beginPress() {
        pressed = true
        anchored = false
        moved = false
        fired = false
        scrollRaw = 0
        anchorWaits = 0
        if (activeSlot >= 0) {
            if (slotX[activeSlot] >= 0) {
                posX = slotX[activeSlot]
                haveX = true
            }
            if (slotY[activeSlot] >= 0) {
                posY = slotY[activeSlot]
                haveY = true
            }
        }
        lastRawX = posX
        lastRawY = posY
        peakMajor = 0
        if (activeSlot >= 0) peakMajor = slotMajor[activeSlot]
        gestureId = gestureSeq.incrementAndGet()
        downAt = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "down gesture=$gestureId slot=$activeSlot have=$haveX,$haveY raw=($posX,$posY) major=$peakMajor")
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ checkHold() }, pressTimeoutMs)
        main.postDelayed({ previewIfStable() }, PREVIEW_DELAY_MS)
    }

    /** 这一帧的多点坐标到齐后再定起点。旧的 ABS_X 不算，它和当前手指不是同一个数。 */
    private fun anchorIfNeeded() {
        if (!pressed || anchored || !haveX || !haveY) return
        anchored = true
        originX = posX
        originY = posY
        scrollRaw = 0
        lastRawX = posX
        lastRawY = posY
        Log.i(TAG, "anchor raw=($originX,$originY) slot=$activeSlot")
    }

    private fun endPress() {
        if (pressed) {
            val held = android.os.SystemClock.uptimeMillis() - downAt
            Log.i(TAG, "up raw=($posX,$posY) fired=$fired major=$peakMajor held=${held}ms")
        }
        pressed = false
        anchored = false
        moved = false
        fired = false
        haveX = false
        haveY = false
        activeSlot = -1
        currentSlot = 0
        scrollRaw = 0
        gestureId = gestureSeq.incrementAndGet()
        main.removeCallbacksAndMessages(null)
    }

    private fun checkHold() {
        if (!running.get() || !pressed || fired) return
        if (!anchored) {
            if (anchorWaits++ < 10) {
                main.postDelayed({ checkHold() }, 40)
                return
            }
            Log.i(TAG, "not anchored, skip")
            return
        }
        if (moved) {
            Log.i(TAG, "hold skipped, finger scrolling")
            return
        }
        val held = android.os.SystemClock.uptimeMillis() - downAt
        if (held < pressTimeoutMs) {
            main.postDelayed({ checkHold() }, pressTimeoutMs - held)
            return
        }
        if (peakMajor < minTouchMajor) {
            Log.i(TAG, "hold waiting thumb area major=$peakMajor min=$minTouchMajor held=${held}ms")
            main.postDelayed({ checkHold() }, 50)
            return
        }
        fired = true
        val sx = screenX()
        val sy = screenY()
        Log.i(TAG, "PRESS ($sx,$sy) raw=($posX,$posY) major=$peakMajor held=${held}ms gesture=$gestureId")
        onPress(sx, sy, held, gestureId)
    }

    /** 菜单弹出前先把手指下的字记下。坐标用锚定后的位置。 */
    private fun previewIfStable() {
        if (!running.get() || !pressed || fired || moved) return
        if (!anchored) {
            main.postDelayed({ previewIfStable() }, 40)
            return
        }
        Log.i(TAG, "preview (${screenX()},${screenY()}) gesture=$gestureId")
        onStable(screenX(), screenY(), gestureId)
    }

    private fun screenX(): Int =
        (posX.toFloat() / maxX.get() * displayW).toInt().coerceIn(0, displayW - 1)

    private fun screenY(): Int =
        (posY.toFloat() / maxY.get() * displayH).toInt().coerceIn(0, displayH - 1)

    private fun noteMove() {
        if (!anchored) return
        val slop = slopRaw()
        val step = kotlin.math.abs(posX - lastRawX) + kotlin.math.abs(posY - lastRawY)
        lastRawX = posX
        lastRawY = posY
        // 一帧里跳过半个屏幕，是别的触点串进来的坏坐标，不是手指在滑。
        if (step > slop * 8) {
            originX = posX
            originY = posY
            scrollRaw = 0
            moved = false
            Log.i(TAG, "jump reanchor raw=($posX,$posY) step=$step")
            return
        }
        val far = kotlin.math.abs(posX - originX) > slop || kotlin.math.abs(posY - originY) > slop
        if (!far) {
            scrollRaw = 0
            moved = false
            return
        }
        scrollRaw += step
        if (scrollRaw > slop * 8) {
            moved = true
            Log.i(TAG, "scrolling, no bang raw=($posX,$posY) origin=($originX,$originY)")
        }
    }

    private fun readFrame(frame: ByteArray) {
        val type = readShort(frame, 16)
        val code = readShort(frame, 18)
        val value = readInt(frame, 20)
        when {
            // 这块屏最多 10 个触点。不看槽位的话，别的手指或 ABS_X 会把坐标一下甩到屏幕另一头，
            // 长按就被当成滑动取消。ABS_X/ABS_Y 是旧坐标，和当前槽位不是同一次采样，忽略。
            type == EV_ABS && code == ABS_MT_SLOT -> currentSlot = value.coerceIn(0, 9)
            type == EV_ABS && code == ABS_MT_TRACKING_ID -> {
                if (value < 0) {
                    if (currentSlot == activeSlot) endPress()
                } else if (activeSlot < 0) {
                    activeSlot = currentSlot
                    if (!pressed) beginPress()
                }
            }
            type == EV_ABS && code == ABS_MT_TOUCH_MAJOR -> onTouchMajor(value)
            type == EV_ABS && code == ABS_MT_POSITION_X -> onSlotPosition(x = value)
            type == EV_ABS && code == ABS_MT_POSITION_Y -> onSlotPosition(y = value)
            type == EV_KEY && code == BTN_TOUCH -> {
                if (value == 1) {
                    if (!pressed) beginPress()
                } else if (pressed) {
                    endPress()
                }
            }
            type == EV_SYN && code == SYN_REPORT -> anchorIfNeeded()
        }
    }

    private fun onTouchMajor(value: Int) {
        val slot = currentSlot.coerceIn(0, 9)
        slotMajor[slot] = value
        if (activeSlot >= 0 && slot != activeSlot) return
        if (value > peakMajor) peakMajor = value
    }

    private fun onSlotPosition(x: Int? = null, y: Int? = null) {
        val slot = currentSlot
        if (x != null) slotX[slot] = x
        if (y != null) slotY[slot] = y
        if (activeSlot < 0) {
            if (!pressed) return
            activeSlot = slot
        }
        if (slot != activeSlot) return
        if (x != null) {
            posX = x
            haveX = true
        }
        if (y != null) {
            posY = y
            haveY = true
        }
        if (pressed) noteMove()
    }

    /**
     * 喂一段 evdev 原始帧（每次 24 字节），用于在没有真手指时验证解析链路。
     * 仅 adb 调试入口调用。
     */
    fun feedFrames(bytes: ByteArray) {
        var off = 0
        val one = ByteArray(FRAME_BYTES)
        while (off + FRAME_BYTES <= bytes.size) {
            System.arraycopy(bytes, off, one, 0, FRAME_BYTES)
            readFrame(one)
            off += FRAME_BYTES
        }
    }

    private fun readShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun parseProbe(out: String): String? {
        var currentDevice: String? = null
        var currentTouch = false
        var currentMaxX = 0
        var currentMaxY = 0
        var bestDevice: String? = null
        var bestMaxX = 0
        var bestMaxY = 0
        fun commit() {
            if (currentTouch && currentDevice != null && currentMaxX > 0 && currentMaxY > 0) {
                bestDevice = currentDevice
                bestMaxX = currentMaxX
                bestMaxY = currentMaxY
            }
        }
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.startsWith("add device")) {
                commit()
                currentDevice = line.substringAfter(":").trim()
                currentTouch = false
                currentMaxX = 0
                currentMaxY = 0
                continue
            }
            val nameMatch = Regex("name:\\s+\"([^\"]*)\"").find(line)
            if (nameMatch != null) {
                val n = nameMatch.groupValues[1].lowercase()
                if (n.contains("touch") || n.contains("goodix") || n.contains("focal") ||
                    n.contains("fts") || n.contains("synaptics")
                ) currentTouch = true
                continue
            }
            if (line.contains("BTN_TOUCH") || line.contains("BTN_TOOL_FINGER")) currentTouch = true
            Regex("ABS_MT_POSITION_X.*max\\s+(\\d+)").find(line)?.let {
                currentMaxX = it.groupValues[1].toInt()
            }
            Regex("ABS_MT_POSITION_Y.*max\\s+(\\d+)").let { r ->
                r.find(line)?.let { currentMaxY = it.groupValues[1].toInt() }
            }
        }
        commit()
        if (bestMaxX > 0) maxX.set(bestMaxX)
        if (bestMaxY > 0) maxY.set(bestMaxY)
        return bestDevice
    }
}
