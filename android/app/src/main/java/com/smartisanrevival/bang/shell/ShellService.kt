package com.smartisanrevival.bang.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class ShellService() : IShellService.Stub() {

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    @Volatile private var streamProc: Process? = null
    @Volatile private var deviceStream: FileInputStream? = null
    private val deviceReading = AtomicBoolean(false)

    override fun runForOutput(cmd: Array<out String>): String {
        val p = Runtime.getRuntime().exec(cmd)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    override fun runStreaming(cmd: Array<out String>): ParcelFileDescriptor {
        stopStreaming()
        val proc = Runtime.getRuntime().exec(cmd)
        streamProc = proc
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                proc.inputStream.use { src ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { dst ->
                        src.copyTo(dst)
                    }
                }
            } catch (e: Exception) {
                Log.i("BangPress", "stream copy end: ${e.message}")
            }
        }, "bang-getevent-copy").start()
        return pipe[0]
    }

    /**
     * 直接读 /dev/input 的二进制事件。getevent -ql 的文本输出经管道会整块缓冲，
     * 实机上读不到实时行；二进制 evdev 结构固定 24 字节，没有这个问题。
     */
    override fun streamDevice(path: String): ParcelFileDescriptor {
        stopStreaming()
        val stream = FileInputStream(path)
        deviceStream = stream
        deviceReading.set(true)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            val buf = ByteArray(24 * 64)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { dst ->
                    while (deviceReading.get()) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        // 只转发完整的事件帧
                        val usable = n - (n % 24)
                        if (usable > 0) dst.write(buf, 0, usable)
                    }
                }
            } catch (e: Exception) {
                Log.i("BangPress", "device stream end: ${e.message}")
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                deviceStream = null
            }
        }, "bang-evdev-copy").start()
        return pipe[0]
    }

    override fun injectMotion(action: String, x: Int, y: Int) {
        try {
            Runtime.getRuntime().exec(
                arrayOf("/system/bin/input", "motionevent", action, x.toString(), y.toString())
            ).waitFor()
        } catch (e: Exception) {
            Log.e("BangPress", "inject $action failed", e)
        }
    }

    override fun stopStreaming() {
        deviceReading.set(false)
        try {
            deviceStream?.close()
        } catch (_: Exception) {
        }
        deviceStream = null
        try {
            streamProc?.destroy()
        } catch (_: Exception) {
        }
        streamProc = null
    }
}
