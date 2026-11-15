package com.smartisanrevival.onestep.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一步自己的 Shizuku UserService。与 bang 的 ShellService 完全独立（不同 tag、不同进程），
 * 原因：那个实现是单流槽位的，第二个 streamDevice() 会杀掉大爆炸按压检测的流。
 */
class OneStepShellService() : IOneStepShellService.Stub() {

    @Suppress("unused")
    constructor(context: Context) : this()

    override fun runForOutput(cmd: Array<out String>?): String {
        if (cmd == null) return ""
        val proc = Runtime.getRuntime().exec(cmd)
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor()
        return if (out.isNotBlank()) out else err
    }

    private val pumping = AtomicBoolean(false)

    /** 把 /dev/input 设备的原始 evdev 流持续泵进管道；多路消费互不影响（evdev 事件是广播给所有读者的）。 */
    override fun streamDevice(path: String?): ParcelFileDescriptor? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.canRead()) return null
        val fds = ParcelFileDescriptor.createPipe()
        val readEnd = fds[0]
        val writeEnd = fds[1]
        Thread({
            var fis: FileInputStream? = null
            try {
                pumping.set(true)
                fis = FileInputStream(file)
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    val buf = ByteArray(8192)
                    while (pumping.get()) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                pumping.set(false)
                try { fis?.close() } catch (_: Exception) {}
                try { writeEnd.close() } catch (_: Exception) {}
            }
        }, "onestep-evdev-copy").start()
        return readEnd
    }
}
