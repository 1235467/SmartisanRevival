package com.smartisanrevival.bang.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.smartisanrevival.bang.BangPrefs
import com.smartisanrevival.bang.overlay.BangOverlayController
import com.smartisanrevival.bang.press.PressWatcher
import com.smartisanrevival.bang.text.NodeTextExtractor
import com.smartisanrevival.bang.text.WordSegmenter
import java.util.concurrent.Executors

/**
 * 大爆炸引擎。触发只走拇指按压（Shizuku 读触摸流），不再监听选区/弹窗。
 * 按住文字达到长按阈值 → 炸开该节点全文。
 */
class BangAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Bang"
        @Volatile
        var instance: BangAccessibilityService? = null
            private set
        private const val COOLDOWN_MS = 700L
        private const val RETRY_DELAY_MS = 3000L
        private const val RETRY_MAX = 40
    }

    private val overlay by lazy { BangOverlayController(this) }
    private val main = Handler(Looper.getMainLooper())
    private val extractor = Executors.newSingleThreadExecutor()
    private var pressWatcher: PressWatcher? = null
    private var panelShowing = false
    private var lastTriggerAt = 0L
    @Volatile private var previewGen = Int.MIN_VALUE
    @Volatile private var preview: com.smartisanrevival.bang.model.BangResult? = null
    private var retryCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 能复制的字在 TextView 里。QQ / 微信把消息标成敏感数据，普通无障碍服务看不见，
        // 只有辅助功能工具（和系统的界面检查一样）能读到。备忘录没藏，所以之前只有它能炸。
        val info = serviceInfo
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        markAsAccessibilityTool(info)
        serviceInfo = info
        Log.i(TAG, "service connected flags=${info.flags}")
        // jieba 首次分词要加载词典，实机实测 10 秒。放到连接时预热，别让第一次按压吃这笔开销。
        extractor.execute {
            val t0 = SystemClock.uptimeMillis()
            WordSegmenter.tokenize("预热分词")
            Log.i(TAG, "segmenter warmup ${SystemClock.uptimeMillis() - t0}ms")
        }
        if (BangPrefs.pressEnabled(this)) startPressWatcherIfReady()
        main.removeCallbacks(watchToken)
        main.postDelayed(watchToken, 3000)
    }

    /** 按压线程死了不会自己回来。之前就是这样：命令还能炸，手指按住没人听。 */
    private val watchToken: Runnable = Runnable {
        if (instance == null) return@Runnable
        if (BangPrefs.pressEnabled(this) && pressWatcher?.isReading() != true) {
            Log.i(TAG, "press watcher not reading, restart")
            pressWatcher?.stop()
            pressWatcher = null
            startPressWatcherIfReady()
        }
        main.postDelayed(watchToken, 5000)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        pressWatcher?.stop()
        pressWatcher = null
        overlay.dismiss(immediate = true)
        extractor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 自动选区/弹窗触发已永久关闭。取字只在按压回调里主动查树。
    }

    /**
     * 重启后 Shizuku 的服务端往往比无障碍服务更晚就绪。之前这里一旦检查失败就永久放弃，
     * 于是必须手动重开一次无障碍才有反应。现在改为在限定次数内定期重试。
     */
    fun startPressWatcherIfReady() {
        if (pressWatcher?.isRunning() == true) return
        val ready = try {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == 0
        } catch (_: Exception) {
            false
        }
        if (!ready) {
            if (retryCount < RETRY_MAX) {
                retryCount++
                Log.i(TAG, "shizuku not ready, retry $retryCount/$RETRY_MAX in ${RETRY_DELAY_MS}ms")
                main.removeCallbacksAndMessages(retryToken)
                main.postDelayed(retryToken, RETRY_DELAY_MS)
            } else {
                Log.i(TAG, "shizuku still not ready after $RETRY_MAX tries, give up")
            }
            return
        }
        retryCount = 0
        val dm = resources.displayMetrics
        // 系统长按约 400ms。轻点也会到这个数。拇指按住要再长一点，再加接触面积。
        val timeout = 550L
        try {
            pressWatcher = PressWatcher(
                dm.widthPixels,
                dm.heightPixels,
                timeout,
                onStable = { x, y, gesture -> previewAt(x, y, gesture) },
                onPress = { x, y, dur, gesture ->
                    Log.i(TAG, "thumb press ($x,$y) ${dur}ms g=$gesture")
                    pressBang(x, y, gesture)
                }
            ).also {
                Log.i(TAG, "press watcher starting timeout=${timeout}ms")
                it.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start press watcher failed", e)
            pressWatcher = null
        }
    }

    private val retryToken = Runnable { startPressWatcherIfReady() }

    /** 系统只从 XML 读这个标记，公开接口写不了。调试包可以反射写上，重装后立刻生效。 */
    private fun markAsAccessibilityTool(info: AccessibilityServiceInfo) {
        try {
            val field = AccessibilityServiceInfo::class.java.getDeclaredField("mIsAccessibilityTool")
            field.isAccessible = true
            field.setBoolean(info, true)
        } catch (e: Exception) {
            Log.i(TAG, "mark tool failed: ${e.message}")
        }
    }

    fun stopPressWatcher() {
        main.removeCallbacksAndMessages(retryToken)
        retryCount = 0
        pressWatcher?.stop()
        pressWatcher = null
    }

    /**
     * 按下约 180ms 就取字。QQ / 微信在系统长按（约 400ms）时会弹出自己的复制菜单，
     * 菜单一出来，手指下面就不再是那条消息。等按压成立时直接用这次提前取到的结果。
     */
    private fun previewAt(x: Int, y: Int, gesture: Int) {
        if (panelShowing) return
        extractor.execute {
            val result = try {
                NodeTextExtractor.extractAtPoint(this, x, y)
            } catch (e: Exception) {
                Log.e(TAG, "preview failed", e)
                null
            }
            if (result == null) {
                Log.i(TAG, "preview miss ($x,$y) g=$gesture")
                return@execute
            }
            preview = result
            previewGen = gesture
            Log.i(TAG, "preview hit g=$gesture pkg=${result.sourceWindowPkg} chars=${result.sourceText.length}")
        }
    }

    /**
     * 取树放在后台线程：实机上取树加首次分词要几百毫秒，占住读取线程会漏掉手指抬起。
     * gesture 与 previewAt 相同才用提前取到的字。调试广播不带 gesture。
     */
    fun pressBang(x: Int, y: Int, gesture: Int = Int.MIN_VALUE) {
        if (panelShowing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now

        val t0 = SystemClock.uptimeMillis()
        extractor.execute {
            val cached = if (gesture != Int.MIN_VALUE && previewGen == gesture) preview else null
            val result = cached ?: try {
                NodeTextExtractor.extractAtPoint(this, x, y)
            } catch (e: Exception) {
                Log.e(TAG, "extract failed", e)
                null
            }
            if (cached != null) Log.i(TAG, "use preview g=$gesture")
            if (result == null) {
                Log.i(TAG, "press on non-text ($x,$y), silent ${SystemClock.uptimeMillis() - t0}ms")
                return@execute
            }
            Log.i(TAG, "bang! pkg=${result.sourceWindowPkg} blocks=${result.blocks.size} ${result.extractMs}ms")
            Log.i(TAG, "first: " + result.blocks.take(12).joinToString("|") { it.text })
            main.post { bang(result) }
        }
    }

    fun triggerManual() {
        if (panelShowing) return
        extractor.execute {
            val result = NodeTextExtractor.extractLargest(this)
            if (result == null) {
                Log.i(TAG, "manual bang: no text")
                return@execute
            }
            main.post { bang(result) }
        }
    }

    private fun bang(result: com.smartisanrevival.bang.model.BangResult) {
        panelShowing = true
        overlay.show(result) { panelShowing = false }
    }
}
