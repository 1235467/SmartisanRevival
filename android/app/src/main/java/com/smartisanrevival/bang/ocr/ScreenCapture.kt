package com.smartisanrevival.bang.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ScreenCapture {
    private const val TAG = "BangOcr"
    /** Flyme 连续截屏会回 INTERVAL，隔一小会再试。 */
    private const val INTERVAL = 3

    fun take(service: AccessibilityService, timeoutMs: Long = 2500): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        repeat(3) { attempt ->
            val bmp = takeOnce(service, timeoutMs)
            if (bmp != null) return bmp
            Log.i(TAG, "screenshot attempt ${attempt + 1} empty, wait")
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun takeOnce(service: AccessibilityService, timeoutMs: Long): Bitmap? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Bitmap?>()
        val err = AtomicReference<String?>()
        val main = Handler(Looper.getMainLooper())
        val start = Runnable {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    { r -> main.post(r) },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hw = result.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                                box.set(wrapped?.copy(Bitmap.Config.ARGB_8888, false))
                                wrapped?.recycle()
                                hw.close()
                            } catch (e: Exception) {
                                err.set("copy ${e.message}")
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            err.set("code=$errorCode")
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                err.set("throw ${e.message}")
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            start.run()
        } else {
            main.post(start)
        }
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            Log.i(TAG, "screenshot timeout ${timeoutMs}ms")
            return null
        }
        val fail = err.get()
        if (fail != null) Log.i(TAG, "screenshot fail $fail")
        val bmp = box.get()
        if (bmp != null) Log.i(TAG, "screenshot ${bmp.width}x${bmp.height}")
        return bmp
    }
}
